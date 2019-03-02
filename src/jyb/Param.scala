package jyb

import java.io.Serializable
import java.nio.file.{Files, Paths}

import scala.xml.XML

class Param extends Serializable {

  private var l = 1.0
  private var eta = 0.05
  private var rank = 10
  private var dropRate = 0.05
  private var margin = 1.0
  private var negNum = 20

  def this(dir: String) {
    this()
    require(dirExist(dir), "dir invalid!")
    val xmlReader =
      XML.loadFile(dir)
    this.margin =
      getParam(xmlReader, "margin").toDouble
    this.negNum =
      getParam(xmlReader, "negNum").toInt
    this.l =
      getParam(xmlReader, "l").toDouble
    this.rank =
      getParam(xmlReader, "rank").toInt
    this.eta =
      getParam(xmlReader, "eta").toDouble
    this.dropRate =
      getParam(xmlReader, "drop").toDouble
  }

  def getClipNorm: Double = this.l
  def getEta: Double = this.eta
  def getRank: Int = this.rank
  def getDropRate: Double = this.dropRate
  def getMargin: Double = this.margin
  def getNegNum: Int = this.negNum

  private def getParam(reader: xml.Elem, x: String):
  String = {
    (reader \\ x).text
  }

  private def dirExist(dir: String):
  Boolean = {
    val path = Paths.get(dir)
    Files.exists(path)
  }

}
