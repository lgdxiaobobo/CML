package jyb

import org.apache.spark
import spark.rdd.RDD
import spark.storage.StorageLevel
import spark.Partitioner
import spark.{HashPartitioner => FMPart}

import spark.sql
import sql.{SparkSession, DataFrame}
import sql.functions._
import scala.util.Random

case class FMModel(param: Param, batchPerIter: Int) {

  // load parameters
  private val drop = param.getDropRate
  private val rank = param.getRank
  private val eta = param.getEta
  private val epsilon = 0.00001
  private val margin = param.getMargin
  private val negNum = param.getNegNum
  private val clipNorm = param.getClipNorm

  private var numUserBlocks = 32
  def setUserBlocks(x: Int):
  this.type = {
    this.numUserBlocks = x
    this
  }

  private var batchSize = 100
  def setBatchSize(x: Int):
  this.type = {
    batchSize = x
    this
  }

  private var intermediateStorage =
    StorageLevel.MEMORY_AND_DISK
  private var finalStorage =
    StorageLevel.MEMORY_AND_DISK
  def setStorageLevel(inter: StorageLevel, last: StorageLevel):
  this.type = {
    this.intermediateStorage = inter
    this.finalStorage = last
    this
  }

  private var checkDir =
    "/checkPoint/cml"
  def setCheckDir(dir: String):
  this.type = {
    this.checkDir = dir
    this
  }

  // fml training
  def train(R: RDD[Usage], valid: DataFrame,
            seed: Long, maxIter: Int):
  (RDD[(Int, Factor)], Map[Int, Factor]) = {
    val ss = valid.sparkSession
    val sc = ss.sparkContext
    val userPart = new FMPart(numUserBlocks)
    // rid off dependency
    val (matY, items) = process(R)
    val nItems = items.length
    // part Y by users
    val partMatY = partUsage(matY, userPart)
      .persist(intermediateStorage)
    // get so-called inblocks
    val userInBlocks = makeBlock(partMatY, userPart)
      .persist(intermediateStorage)
    // random generator
    val seedGen = new Random(seed)
    var userFactors =
      initialize(userInBlocks, rank, seedGen.nextLong())
    var itemFactors =
      initialize(items, rank, seedGen.nextLong())
    var userSumGrads =
      userFactors.mapValues{fs => fs.map(_ => 0.0)}
    var itemSumGrads =
      itemFactors.map{case (k, _) => (k, 0.0)}
    // training with drop-out
    for (step <- 0 until maxIter){
      // store user_with_factor_in_blocks
      userFactors = 
        checkFactors(userFactors, checkDir, "userFactors")      
      // store user_with_sum_of_grads^2_in_blocks
      userSumGrads =
        checkSumGrads(userSumGrads, checkDir, "userGrads")      
      // get loss
      val trainLoss =
        getBlockedRankLoss(userInBlocks, userFactors, itemFactors)
      val userWithFactor =
        userInBlocks.join(userFactors).mapValues{
          case (block, factors) =>
            block.srcIds.view.zip(factors)
        }.flatMap(_._2).setName(s"$step-W")
          .persist(intermediateStorage)
      val validLoss =
        getRankLoss(valid, userWithFactor, itemFactors)
      userWithFactor.unpersist()
      println(s"[$step rank loss] train: $trainLoss, valid: $validLoss")
      // batch-gd
      for (iter <- 0 until batchPerIter){
        val iterSeed = seedGen.nextLong() | iter ^ step
        val preUserFactors =
          userFactors.setName(s"$iter-W")
            .persist(intermediateStorage)
        // get gradient
        val (userGrads, itemGrads) =
          learnGradient(userInBlocks, preUserFactors, 
            itemFactors, nItems, iterSeed)
        // adagrad
        val preUserSumGrads =
          userSumGrads.setName(s"$iter-SGW")
            .persist(intermediateStorage)
        userSumGrads =
          getSumGrads(preUserSumGrads, userGrads)
        itemSumGrads =
          itemGrads.foldLeft(itemSumGrads){
            case (dict, (k, gk)) =>
              dict + (k -> (dict(k) + norm2(gk)))
          }
        // update by gradient with AdaGrad method
        userFactors =
          updatedFactor(preUserFactors, userSumGrads, userGrads)
        itemFactors =
          itemGrads.foldLeft(itemFactors){
            case (dict, (i, ghi)) =>
              val _eta =
                div(eta, math.sqrt(itemSumGrads(i)))
              val newHi = plus(dict(i), ghi, -_eta)
              dict + (i -> clip(newHi, clipNorm))
          }
        if (iter % 5 == 0){
          userFactors =
            checkFactors(userFactors, checkDir, "userFactors")
          userSumGrads =
            checkSumGrads(userSumGrads, checkDir, "userGrads")
        }
        preUserSumGrads.unpersist()
        preUserFactors.unpersist()
      }
    }
    val userWithFactor =
      userInBlocks.join(userFactors).mapValues{
        case (block, factors) =>
          block.srcIds.view.zip(factors)
      }.flatMap(_._2)
    userInBlocks.unpersist()
    partMatY.unpersist()
    (userWithFactor, itemFactors)
  }

  def getRankLoss(valid: DataFrame,
                  matW: RDD[(Int, Array[Double])],
                  matH: Map[Int, Array[Double]]):
  Double = {
    // assume valid: Row(u, pos0, pos1)
    val ss = valid.sparkSession
    val sc = ss.sparkContext
    import ss.implicits._
    val rDF = valid.toDF("u", "pos0", "pos1")
      .as("R").persist(intermediateStorage)
    val wDF = matW.toDF("u", "wu")
      .as("W").persist(intermediateStorage)
    val t1 = rDF.join(wDF, rDF("u") === wDF("u"))
      .select("R.pos0", "R.pos1", "W.wu")
      .toDF("pos0", "pos1", "wu")
      .as("T1").persist(intermediateStorage)
    t1.count()
    rDF.unpersist()
    wDF.unpersist()
    val hBD = sc.broadcast(matH)
    val loss = t1.as[(Array[Int], Array[Int], Array[Double])].rdd
      .map{case (pos0, pos1, wu) => rankLoss(pos0, pos1, wu, hBD.value)}
      .mean()
    t1.unpersist()
    loss
  }

  def getBlockedRankLoss(matR: RDD[(Int, InBlock)], 
                         matW: RDD[(Int, Array[Factor])], 
                         matH: Map[Int, Factor]):
  Double = {
    val sc = matR.sparkContext
    val hBD = sc.broadcast(matH)
    val errors = matR.join(matW).mapValues{
      case (block, factors) =>
        val srcIds = block.srcIds
        val dstPtrs = block.dstPtrs
        val dstIndices = block.dstIndices
        val errorsBuilder = 
          collection.mutable.ArrayBuilder.make[Double]
        for (u <- srcIds.indices){
          val wu = factors(u)
          // distance from items to users
          val allScores = 
            hBD.value.map{
              case (i, hi) =>
                (i, distance2(wu, hi))
            }
          // indices about used items
          val pu = getIndices(dstIndices, 
            dstPtrs(u), dstPtrs(u+1))
          // get rank for each items
          val used = pu.toSet
          pu.foreach{i =>
            val dui = allScores(i)
            val (_emax, _rank) = 
              allScores.foldLeft((0.0, 0)){
                case ((e0, r0), (j, duj)) =>
                  if (used.contains(j))
                    (e0, r0)
                  else{
                    val dij = dui - duj
                    if (dij >= 0) {
                      val e1 = 
                        if (dij >= e0) dij else e0
                      val r1 = r0 + 1
                      (e1, r1)
                    }else{
                      (e0, r0)
                    }
                  }
              }
            errorsBuilder += 
              _emax * math.log(_rank + 2.0) / math.log(2.0)
          }
        }
        errorsBuilder.result()
    }.flatMap(_._2)
    errors.mean()
  }

  private def getGaussian(rng: Random,
                          sigma: Double,
                          mu: Double):
  Double =
    rng.nextGaussian() * sigma + mu


  private def initialize(keys: Array[Int],
                         d: Int, seed: Long):
  Map[Int, Factor] = {
    val sz = keys.length
    val rng = new Random(seed ^ sz)
    keys.map{k =>
      val factor =
        Array.fill(d)(getGaussian(rng, 0.03, 0.08))
      (k, factor)
    }.toMap
  }

  private def initialize(keys: RDD[(Int, InBlock)],
                         d: Int, seed: Long):
  RDD[(Int, Array[Factor])] = {
    val sz = keys.count()
    val rng = new Random(seed ^ sz)
    keys.mapPartitions({ps =>
      ps.map{
        case (id, block) =>
          val sz = block.size
          val factors = Array.fill(sz){
            Array.fill(d)(getGaussian(rng, 0.03, 0.08))
          }
          (id, factors)
      }
    }, true)
  }

  private def makeBlock(R: RDD[(Int, Usages)],
                        part: Partitioner):
  RDD[(Int, InBlock)] = {
    R.mapPartitions({items =>
      items.map{case (id, us) =>
        val builder = InBlockBuilder()
        builder.add(us)
        (id, builder.build())
      }
    }, preservesPartitioning = true)
      .setName("InBlock")
  }

  private def partUsage(R: RDD[Usage],
                        part: Partitioner):
  RDD[(Int, Usages)] = {
    val numPartitions = part.numPartitions
    R.mapPartitions{ps =>
      val builders =
        Array.fill(numPartitions)(UsagesBuilder())
      ps.flatMap{r =>
        val blockId = part.getPartition(r.u)
        val builder = builders(blockId)
        builder.add(r)
        if (builder.size >= 2048){
          builders(blockId) = UsagesBuilder()
          Iterator.single((blockId, builder.build()))
        }else Iterator.empty
      } ++ {
        builders.view.zipWithIndex
          .filter(_._1.size > 0).map{
          case (block, idx) =>
            (idx, block.build())
        }
      }
    }.groupByKey(numPartitions).mapValues{ps =>
      val builder = UsagesBuilder()
      ps.foreach(builder.merge)
      builder.build()
    }.setName("Usages")
  }

  def eval(test: DataFrame,
           matW: RDD[(Int, Factor)],
           matH: Map[Int, Factor]):
  Double =
    getRankLoss(test, matW, matH)

  def updatedFactor(factors: RDD[(Int, Array[Factor])],
                    sumGrads: RDD[(Int, Array[Double])],
                    grads: RDD[(Int, Array[Factor])]):
  RDD[(Int, Array[Factor])] = {
    val delta = sumGrads.join(grads).mapValues{
      case (sgs, gs) =>
        sgs.zip(gs).map{case (sg, g) =>
          weighted(g, div(eta, math.sqrt(sg)))
        }
    }.setName("Delta").persist(intermediateStorage)
    val newFactors = factors.join(delta).mapValues{
      case (fs, ds) =>
        fs.zip(ds).map{
          case (f, d) =>
            clip(plus(f, d, -1.0), clipNorm)
        }
    }
    newFactors.count()
    delta.unpersist()
    newFactors
  }

  def getSumGrads(sumGrads: RDD[(Int, Array[Double])],
                  grads: RDD[(Int, Array[Factor])]):
  RDD[(Int, Array[Double])] = {
    sumGrads.join(grads).mapValues{
      case (sgs, gs) =>
        sgs.zip(gs).map{case (sg, g) =>
          sg + norm2(g)
        }
    }
  }

  def learnGradient(srcInBlock: RDD[(Int, InBlock)],
                    srcFactors: RDD[(Int, Array[Factor])],
                    matH: Map[Int, Factor], nItems: Int, 
                    seed0: Long):
  (RDD[(Int, Array[Factor])], Array[(Int, Factor)]) = {
    val sc = srcInBlock.sparkContext
    val hBD = sc.broadcast(matH)   
    val rng = new util.Random(seed0)
    // only max {w(u,i) * |m+d(u,i)^2-d(u,i')^2|} would be considered
    val joint = srcInBlock.join(srcFactors)
    .mapValues{ case (block, factors) =>
      val dstPtrs = block.dstPtrs
      val dstIndices = block.dstIndices
      val items = hBD.value.keys.toArray
      val batches = 
        getBatches(dstPtrs, dstIndices, items, 
          batchSize, negNum, rng.nextLong())
      val gu = 
        factors.map(f => f.map(_ => 0.0))
      val giBuilder =
        collection.mutable.ArrayBuilder.make[(Int, Factor)]
      batches.foreach{case (u, i, js) =>
        val wu = factors(u)
        val hi = hBD.value(i)
        val _dui = plus(wu, hi, -1.0)
        val _fui = dropOut(_dui, drop, rng.nextLong())
        val dui = norm2(_fui)
        // get rank(u, i)
        // get max{margin + dui - duj}
        val _fujs = js.map{j =>
          val hj = hBD.value(j)
          val _duj = plus(wu, hj, -1.0)
          val _fuj = dropOut(_duj, drop, rng.nextLong())
          (j, _fuj)
        }
        val negWithDis = _fujs.map{
          case (j, _fuj) =>
            val duj = norm2(_fuj)
            (j, _fuj, margin + dui - duj)
        } 
        val imposters = negWithDis.filter{
          case (j, _, xuj) => j != i && xuj >= 0
        }
        if (imposters.nonEmpty){
          val rank = 
            predictRank(nItems, imposters.length, negNum)
          val wui = 
            math.log(rank + 2.0) / math.log(2.0)
          val (j, _fuj, _) =
            findMaxNeg(imposters)
          // update gradients based on user
          val _fuij = plus(_fui, _fuj, -1.0)
          gu(u) = plus(gu(u), _fuij, 2 * wui)
          // update gradients based on items
          giBuilder += ((i, weighted(_fui, -2 * wui)))
          giBuilder += ((j, weighted(_fuj, 2 * wui)))
        }
      }
      (gu, giBuilder.result())
    }
    val gu = joint.mapValues(_._1)
    val gi = joint.map(_._2._2).flatMap(p => p)
      .reduceByKey(plus).collect()
    (gu, gi)
  }


  private def process(positive: RDD[Usage]):
  (RDD[Usage], Array[Int]) = {
    val items =
      positive.map(_.i)
        .distinct().collect()
    val Y0 = checkOff[Usage](positive, checkDir, "Usage")
      .map(_.split(',')).map(buildUsage)
    (Y0, items)
  }

}
