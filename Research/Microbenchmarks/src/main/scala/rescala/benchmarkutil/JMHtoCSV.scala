package rescala.benchmarkutil

import java.io.FileWriter

import scala.io.Source


object JMHtoCSV {
  val BENCHMARK = "^# Benchmark: (.*)$".r
  val THREADS = "^# Threads: (\\d+) thread.*$".r
  val PARAMETERS = "^# Parameters: \\((.*)\\)$".r
  val MEASUREMENT = "^Iteration .*: (\\d+)[.,](\\d+) ops/ms$".r

  var benchmark: String = _
  var threads: String = _
  var parameters: String = _

  def main(args: Array[String]): Unit = {
    val out = new FileWriter(args.head)
    try {
      for (fileName <- args.tail) {
        println("processing "+fileName)
        for ((line, lineNo) <- Source.fromFile(fileName).getLines.zipWithIndex) {
          line match {
            case BENCHMARK(benchmarkName) =>
              if (benchmark != null) {
                if (benchmarkName != benchmark) throw new Exception(s"benchmark so far was $benchmark, but now found results for $benchmarkName in $fileName:$lineNo")
              } else {
                benchmark = benchmarkName
              }
            case THREADS(threadCount) =>
              threads = threadCount
            case PARAMETERS(allParams) =>
              if (parameters == null) {
                out.write("threads\t" + allParams.substring(0, allParams.lastIndexOf('=')).replaceAll(" = [^=]+, ", "\t") + "\tmeasurement\n")
              }
              parameters = allParams.substring(allParams.indexOf('=') + 2).replaceAll(", [^=]+ = ", "\t")
            case MEASUREMENT(integer, decimal) =>
              out.write(threads + "\t" + parameters + "\t" + integer + "," + decimal + "\n")
            case _ => // ignore
          }
        }
      }
    } finally {
      out.close()
    }
    println("done, output written to "+args.head)
  }
}
