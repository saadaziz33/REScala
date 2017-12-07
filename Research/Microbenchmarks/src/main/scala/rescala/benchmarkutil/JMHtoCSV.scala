package rescala.benchmarkutil

import java.io.FileWriter

import scala.io.Source


object JMHtoCSV {
  val BENCHMARK = "^# Benchmark: (.*)$".r
  val THREADS = "^# Threads: (\\d+) thread.*$".r
  val PARAMETERS = "^# Parameters: \\((.*)\\)$".r
  val MEASUREMENT = "^Iteration .*: (\\d+)[.,](\\d+) ops/ms$".r

  var outfiles = Map[String, FileWriter]()
  var benchmark: String = _
  var threads: String = _
  var parameters: String = _

  def main(args: Array[String]): Unit = {
    try {
      for (fileName <- args) {
        println("processing "+fileName)
        for ((line, lineNo) <- Source.fromFile(fileName).getLines.zipWithIndex) {
          line match {
            case BENCHMARK(benchmarkName) =>
              benchmark = benchmarkName
            case THREADS(threadCount) =>
              threads = threadCount
            case PARAMETERS(allParams) =>
              parameters = allParams.substring(allParams.indexOf('=') + 2).replaceAll(", [^=]+ = ", "\t")
              if(!outfiles.contains(benchmark)) {
                outfiles += benchmark -> new FileWriter(benchmark + ".txt")
                outfiles(benchmark).write("srcfile\tthreads\t" + allParams.substring(0, allParams.lastIndexOf('=')).replaceAll(" = [^=]+, ", "\t") + "\tmeasurement\n")
              }
            case MEASUREMENT(integer, decimal) =>
              outfiles(benchmark).write(fileName + "\t" + threads + "\t" + parameters + "\t" + integer + "," + decimal + "\n")
            case _ => // ignore
          }
        }
      }
    } finally {
      for(writer <- outfiles.values) writer.close()
    }
    println("done, written files:\n" + outfiles.keySet.mkString(".txt\n") + ".txt")
  }
}
