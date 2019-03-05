import org.apache.hadoop.fs
import fs.FileSystem
import fs.Path

import org.apache.spark
import spark.SparkContext
import spark.rdd.RDD
import spark.broadcast.Broadcast

import spark.sql
import sql.SparkSession
import sql.functions._
import sql.DataFrame

import scala.reflect.ClassTag
import scala.collection.mutable

import java.nio.file.Paths

package object jyb {

  type Factor = Array[Double]

  def distance2(v1: Factor, v2: Factor):
  Double = {
    v1.zip(v2)
      .map{case (a, b) =>
        a - b
      }.map(pow2).sum
  }

  def pow2(x: Double): Double =
    x * x

  def plus(v1: Factor, w1: Double,
           v2: Factor, w2: Double):
  Factor = {
    v1.zip(v2)
      .map{case (a, b) =>
        a * w1 + b * w2
      }
  }

  def plus(v1: Factor, v2: Factor, w2: Double):
  Factor =
    plus(v1, 1.0, v2, w2)

  def plus(v1: Factor, v2: Factor):
  Factor =
    plus(v1, 1.0, v2, 1.0)

  def weighted(v: Factor, w: Double):
  Factor =
    v.map(_ * w)

  def norm2(v: Factor): Double =
    v.map(pow2).sum
  def norm(v: Factor): Double =
    math.sqrt(norm2(v))

  def normalise(v: Factor):
  Factor =
    weighted(v, div(1.0, norm(v)))

  def div(x: Double, y: Double):
  Double =
    if (y == 0.0) 0.0
    else x / y

  def getMeanFactor(fs: Array[Factor]):
  Factor = {
    val sz = fs.length
    val f0 = fs.reduce(plus)
    weighted(f0, div(1.0, sz))
  }

  def clip(f: Factor, l: Double):
  Factor = {
    val fn = norm(f)
    if (fn * fn <= l)
      f
    else {
      val scale = div(math.sqrt(l), fn)
      weighted(f, scale)
    }
  }

  def checkOff[T: ClassTag](data: RDD[T],
                            dir: String,
                            name: String):
  RDD[String] = {
    val path = Paths.get(dir, name).toString
    val sc = data.sparkContext
    deleteIfExists(sc, path)
    data.map(_.toString)
      .repartition(16)
      .saveAsTextFile(path)
    sc.textFile(path)
  }

  def buildFactor(ps: Array[String]): (Int, (Int, Factor)) = {
    val id = ps(0).toInt
    val idx = ps(1).toInt
    val factor =
      ps(2).split(',').map(_.toDouble)
    (id, (idx, factor))
  }

  def checkFactors(factors: RDD[(Int, Array[Factor])],
                   dir: String, name: String):
  RDD[(Int, Array[Factor])] = {
    val path = Paths.get(dir, name).toString
    val sc = factors.sparkContext
    deleteIfExists(sc, path)
    val numPartition = factors.getNumPartitions
    factors.flatMap{case (id, fs) =>
      fs.zipWithIndex.map{case (f, idx) =>
        s"$id|$idx|%s".format(f.mkString(","))
      }
    }.repartition(16).saveAsTextFile(path)
    sc.textFile(path).map(_.split('|'))
      .map(buildFactor).groupByKey(numPartition)
      .mapValues{ps =>
        ps.toArray
          .sortBy(_._1).map(_._2)
      }
  }

  def checkSumGrads(sumGrads: RDD[(Int, Array[Double])],
                    dir: String, name: String):
  RDD[(Int, Array[Double])] = {
    val path = Paths.get(dir, name).toString
    val sc = sumGrads.sparkContext
    deleteIfExists(sc, path)
    val numPartition = sumGrads.getNumPartitions
    sumGrads.flatMap{case (id, gs) =>
      gs.zipWithIndex.map{case (g, idx) =>
        s"$id|$idx|%f".format(g)
      }
    }.repartition(16).saveAsTextFile(path)
    sc.textFile(path).map(_.split('|'))
      .map(ps => (ps(0).toInt, (ps(1).toInt, ps(2).toDouble)))
      .groupByKey(numPartition).mapValues{ps =>
        ps.toArray
          .sortBy(_._1).map(_._2)
      }
  }

  def buildUsage(ps: Array[String]):
  Usage = {
    val u = ps(0).toInt
    val i = ps(1).toInt
    Usage(u, i)
  }

  def deleteIfExists(sc: SparkContext, path: String):
  Boolean = {
    val fs = FileSystem.get(sc.hadoopConfiguration)
    val dir = new Path(path)
    if (fs.exists(dir))
      fs.delete(dir, true)
    true
  }

  def dropOut(f: Factor, drop: Double, seed: Long):
  Factor = {
    val rng = new util.Random(seed)
    val factorBuilder =
     mutable.ArrayBuilder.make[Double]
    f.foreach{fi =>
      val prop = rng.nextDouble()
      if (prop <= drop)
        factorBuilder += 0.0
      else
        factorBuilder += fi
    }
    factorBuilder.result()
  }

  case class Usage(u: Int, i: Int){
    override def toString: String =
      s"$u,$i"
  }
  case class Usages(srcIds: Array[Int], dstIds: Array[Int])
  case class UsagesBuilder(){
    private val srcIdsBuilder =
      mutable.ArrayBuilder.make[Int]
    private val dstIdsBuilder =
      mutable.ArrayBuilder.make[Int]

    var size = 0

    def add(r: Usage): this.type = {
      size += 1
      srcIdsBuilder += r.u
      dstIdsBuilder += r.i
      this
    }

    def merge(other: Usages): this.type = {
      size += other.srcIds.length
      srcIdsBuilder ++= other.srcIds
      dstIdsBuilder ++= other.dstIds
      this
    }

    def build(): Usages = {
      Usages(srcIdsBuilder.result(),
        dstIdsBuilder.result())
    }
  }
  case class InBlock(srcIds: Array[Int],
                     dstPtrs: Array[Int],
                     dstIndices: Array[Int]){
    def size = srcIds.length
    require(dstPtrs.length == srcIds.length + 1)
    require(dstPtrs.last == dstIndices.length)
  }
  case class InBlockBuilder(){
    private val srcIdsBuilder =
      mutable.ArrayBuilder.make[Int]
    private val dstIdsBuilder =
      mutable.ArrayBuilder.make[Int]

    def add(us: Usages): this.type = {
      srcIdsBuilder ++= us.srcIds
      dstIdsBuilder ++= us.dstIds
      this
    }

    def build(): InBlock = {
      val srcIds = srcIdsBuilder.result()
      val dstIds = dstIdsBuilder.result()
      val sortedSrcIds = srcIds.view
        .zipWithIndex.sortBy(_._1)
      val uniqSrcIdsBuilder =
        mutable.ArrayBuilder.make[Int]
      val dstPtrsBuilder =
        mutable.ArrayBuilder.make[Int]
      val dstIndicesBuilder =
        mutable.ArrayBuilder.make[Int]
      val tmp = sortedSrcIds.foldLeft((-1, 0)){
        case ((preSrcId, index), (srcId, idx)) =>
          dstIndicesBuilder += dstIds(idx)
          if (preSrcId == -1){
            uniqSrcIdsBuilder += srcId
            dstPtrsBuilder += index
            (srcId, index + 1)
          }else if (preSrcId != srcId){
            uniqSrcIdsBuilder += srcId
            dstPtrsBuilder += index
            (srcId, index + 1)
          }else (srcId, index + 1)
      }._2
      dstPtrsBuilder += tmp
      InBlock(uniqSrcIdsBuilder.result(),
        dstPtrsBuilder.result(), dstIndicesBuilder.result())
    }
  }

  def getIndices(indices: Array[Int], l: Int, r: Int):
  Array[Int] = {
    val builder = mutable.ArrayBuilder.make[Int]
    for (i <- l until r){
      builder += indices(i)
    }
    builder.result()
  }

  def getBatches(ptrs: Array[Int], indices: Array[Int],
                items: Array[Int], nNeg: Int, 
                size: Int, seed: Long):
  Array[(Int, Int, Array[Int])] = {
    val sz = ptrs.length - 1
    val dz = items.length
    val rng = new util.Random(seed ^ sz)
    Array.fill(size){
      val u = rng.nextInt(sz)
      val idx = 
        rng.nextInt(ptrs(u+1) - ptrs(u)) + ptrs(u)
      val i = indices.apply(idx)
      val js = Array.fill(nNeg){
        items.apply(rng.nextInt(dz))
      }
      (u, i, js)
    }
  }

  def predictRank(J: Int, M: Int, U: Int): Int = 
    J * M / U + 1

  def findMaxNeg(negWithScore: Array[(Int, Factor, Double)]):
  (Int, Factor, Double) = {
    negWithScore.tail.foldLeft(negWithScore.head){
      case ((j0, fj0, sj0), (j1, fj1, sj1)) =>
        if (sj0 >= sj1)
          (j0, fj0, sj0)
        else
          (j1, fj1, sj1)
    }
  }

  def rankLoss(pos0: Array[Int], pos1: Array[Int],
               wu: Factor, matH: Map[Int, Factor]):
  Double = {
    val allItemsWithScore = 
      matH.map{case (j, hj) => (j, distance2(wu, hj))}
    val used = (pos0 ++ pos1).toSet
    val negsWithScores = allItemsWithScore
      .filter{case (j, _) => !used.contains(j)}
    val ranks = pos1.map{i =>
      val dui = allItemsWithScore(i)
      negsWithScores.foldLeft(0){
        case (agg, (_, duj)) =>
          if (duj <= dui)
            agg + 1
          else 
            agg
      }
    }
    val sz = pos1.length
    ranks.map{r => 
      math.log(2.0) /
        math.log(r + 2.0)
    }.sum * 1.0 / sz
  }
}
