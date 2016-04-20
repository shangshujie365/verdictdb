package edu.umich.verdict.expr

import java.io.{PrintWriter, File}
import java.sql.ResultSet
import java.util.Calendar

import edu.umich.verdict.Configuration
import edu.umich.verdict.cli.ResultWriter
import edu.umich.verdict.connectors.DbConnector
import edu.umich.verdict.transformation.Parser

import scala.io.Source

class ErrorEstimationAccuracy() {
  var conf = new Configuration(new File(this.getClass.getClassLoader.getResource("expr/config.conf").getFile))
  var connector: DbConnector = null
  var nSamples = 1000
  var sampleSize = 0.01
  var table = "lineitem40"
  var nPoissonCols = 100
  var queries = Array(
    """
      |select
      |sum(quantity) as sum_qty,
      |sum(extendedprice) as sum_base_price,
      |avg(extendedprice) as avg_price,
      |count(*) as count_order
      |from
      |lineitem40
      |where
      |shipdate <= '1998-09-01'
      |and returnflag = 'A'
      |and linestatus = 'F'
    """.stripMargin)
  var exacts: Array[Array[Double]] = null
  var approximates: Array[Array[Array[ApproxResult]]] = null
  var dir: String = null


  def execute(q: String): ResultSet = {
    Parser.parse(q).run(conf, connector)
  }

  def sampleName(i: Int) = s"error_test_${table}_s$i"

  def createSamples(): Unit = {
    for (i <- 1 to nSamples) {
      try {
        execute(s"create sample ${sampleName(i)} from $table with size $sampleSize% store $nPoissonCols poisson columns")
      } catch {
        case e: Exception => println(e.getMessage)
      }
    }
  }

  def removeSamples(): Unit = {
    for (i <- 1 to nSamples) {
      execute(s"drop sample ${sampleName(i)}")
    }
  }

  def runExacts() = {
    execute("set approximation = off")
    queries.zipWithIndex.foreach(q => {
      val pw = new PrintWriter(new File(s"error-test/$dir/${q._2}/exact"))
      ResultWriter.writeResultSet(pw, execute(q._1))
      pw.close()
    })
  }

  def runApproximates() = {
    execute(s"set bootstrap.sample_size = $sampleSize%")
    execute("set approximation = on")
    queries.zipWithIndex.foreach(q => {
      val pw = new PrintWriter(new File(s"error-test/$dir/${q._2}/approx"))
      for (i <- 1 to nSamples) {
        execute(s"set bootstrap.fixed_sample = ${sampleName(i)}")
        ResultWriter.writeResultSet(pw, execute(q._1))
      }
      pw.close()
    })
  }

  def loadExacts() = {
    exacts = queries.indices.map(i => {
      Source.fromFile(s"error-test/$dir/$i/exact").getLines().toArray.apply(2).split("\\|").map(_.trim.toDouble)
    }).toArray
    exacts
  }

  def loadApproximates() = {
    if (exacts == null)
      loadExacts()
    approximates = queries.indices.map(q => {
      val nCols = exacts(q).length
      var lines = Source.fromFile(s"error-test/$dir/$q/approx").getLines().toArray
      lines = lines.indices.filter(_ % 3 == 2).map(lines).toArray
      (0 until nCols).map(i => {
        lines.map(line => {
          val vals = line.split("\\|").map(_.trim.toDouble)
          ApproxResult(vals(i), vals(nCols + 4 * i), vals(nCols + 4 * i + 1), vals(nCols + 4 * i + 2), vals(nCols + 4 * i + 3))
        })
      }).toArray
    }).toArray
    approximates
  }

  def printBias() = {
    queries.indices.foreach(q => {
      println(s"Bias for query $q:")
      approximates(q).indices.foreach(col => {
        val colVals = approximates(q)(col)
        val avg = colVals.map(_.value).sum / colVals.length
        val exact = exacts(q)(col)
        println(s"column $col: ${math.abs(avg - exact)} (${math.abs(100 * (avg - exact) / exact)}%)")
      })
    })
  }

  def printVarianceError() = {
    def variance(apps: Array[ApproxResult]) = {
      val avg = apps.map(_.value).sum / apps.length
      apps.map(num => (num.value - avg) * (num.value - avg)).sum / apps.length
    }
    queries.indices.foreach(q => {
      println(s"Variance error for query $q:")
      val pw = new PrintWriter(new File(s"error-test/$dir/$q/report-variance-error"))
      approximates(q).indices.foreach(col => {
        val colVals = approximates(q)(col)
        val exact = variance(colVals)
        val errorAvg = colVals.map(app => math.abs((app.variance - exact) / exact)).sum / colVals.length
        println(s"column $col: ${100 * errorAvg}%")
        pw.println(s"column $col: ${100 * errorAvg}%")
      })
      pw.close()
    })
  }

  def connect() = {
    connector = DbConnector.createConnector(conf)
  }

  def printConfidenceIntervalError(method: String = "diff") = {
    def exactConfidenceInterval(exactVal: Double, apps: Array[ApproxResult]) = {
      val confidence = if (conf != null) conf.getPercent("bootstrap.confidence") else .95
      val trials: Int = apps.length
      val sortedVals = apps.map(_.value).sortBy(x => math.abs(exactVal - x))
      val bound = sortedVals(math.ceil(trials * confidence - 1).asInstanceOf[Int])
      (exactVal - math.abs(exactVal - bound),
        exactVal + math.abs(exactVal - bound))
    }

    def confidenceIntervalError(exact: (Double, Double), approx: (Double, Double)) = {
      if (method == "diff")
        (math.abs(exact._1 - approx._1) + math.abs(exact._2 - approx._2)) / (exact._2 - exact._1)
      else if (method == "abs-width")
        math.abs((exact._2 - exact._1) - (approx._2 - approx._1)) / (exact._2 - exact._1)
      else if (method == "width")
        ((approx._2 - approx._1) - (exact._2 - exact._1)) / (exact._2 - exact._1)
      else
        0.0
    }

    queries.indices.foreach(q => {
      println(s"Confidence interval error for query $q ($method):")
      val pw = new PrintWriter(new File(s"error-test/$dir/$q/report-confidence-interval-error-$method"))
      approximates(q).indices.foreach(col => {
        val colVals = approximates(q)(col)
        val exact = exactConfidenceInterval(exacts(q)(col), colVals)
        val errorAvg = colVals.map(app => confidenceIntervalError(exact, (app.ciLow, app.ciHigh))).sum / colVals.length
        println(s"column $col: ${100 * errorAvg}%")
        pw.println(s"column $col: ${100 * errorAvg}%")
      })
      pw.close()
    })
  }

  def printEstimatedErrors() = {
    queries.indices.foreach(q => {
      println(s"Average estimated error for query $q:")
      val pw = new PrintWriter(new File(s"error-test/$dir/$q/report-estimated-error"))
      approximates(q).indices.foreach(col => {
        val colVals = approximates(q)(col)
        val avg = colVals.map(app => math.abs(app.error / app.value)).sum / colVals.length
        println(s"column $col: ${100 * avg}%")
        pw.println(s"column $col: ${100 * avg}%")
      })
      pw.close()
    })
  }

  def run() {
    println("Connecting ...")
    connect()

    createDirs()

    println("Running Exacts ...")
    runExacts()

    //    println("Creating Samples ...")
    //    createSamples()

    println("Running Approximates ...")
    runApproximates()

    loadExacts()
    loadApproximates()

    println("Estimated Answer Error:")
    printEstimatedErrors()

    println("Bias:")
    printBias()

    println("Variance Error:")
    printVarianceError()

    Seq("diff", "abs-width", "width").foreach(method => {
      println(s"Confidence Interval Error ($method):")
      printConfidenceIntervalError(method)
    })

    //    println("Removing Samples ...")
    //    removeSamples()

  }

  def setDir() = {
    val now = Calendar.getInstance()
    dir = s"${now.get(Calendar.MONTH)}-${now.get(Calendar.DAY_OF_MONTH)}.${now.get(Calendar.HOUR_OF_DAY)}:${now.get(Calendar.MINUTE)}"
  }

  def createDirs(): Unit = {
    new File("error-test").mkdir()
    setDir()
    new File(s"error-test/$dir").mkdir()
    val pw = new PrintWriter(new File(s"error-test/$dir/conf"))
    pw.println(s"table = ${table}")
    pw.println(s"nSamples = ${nSamples}")
    pw.println(s"sampleSize = ${sampleSize}")
    pw.println(s"confidence = ${conf.get("bootstrap.confidence")}")
    pw.println(s"trials = ${conf.get("bootstrap.trials")}")
    pw.println(s"method = ${conf.get("bootstrap.method")}")
    pw.println(s"sampleType = ${conf.get("bootstrap.sample_type")}")
    pw.close()
    queries.indices.foreach(i => {
      new File(s"error-test/$dir/$i").mkdir()
      val pw = new PrintWriter(new File(s"error-test/$dir/$i/q"))
      pw.println(queries(i))
      pw.close()
    })
  }
}

object ErrorEstimationAccuracy {
  def run(): Unit = {
    Seq(
      (100, 95, "uda"),
      (100, 95, "udf"),
      (100, 95, "stored")
    ).foreach(conf => {
      val etest = new ErrorEstimationAccuracy()
      etest.nSamples = 100
      etest.conf.set("bootstrap.trials", conf._1 + "")
      etest.conf.set("bootstrap.confidence", conf._2 + "%")
      etest.conf.set("bootstrap.method", conf._3)
      etest.run()
    })
  }
}

case class ApproxResult(value: Double, ciLow: Double, ciHigh: Double, error: Double, variance: Double)


/*
Shell commands:

import edu.umich.verdict.expr._
ErrorEstimationAccuracy.run()

*/