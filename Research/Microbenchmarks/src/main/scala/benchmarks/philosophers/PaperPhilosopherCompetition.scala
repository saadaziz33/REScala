package benchmarks.philosophers

import java.io.{FileOutputStream, PrintStream}
import java.util.concurrent.TimeUnit

import benchmarks.{BusyThreads, EngineParam, Workload}
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.{BenchmarkParams, ThreadParams}
import rescala.core.Struct
import rescala.fullmv.SerializationGraphTracking

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
//@Fork(value=1, jvmArgsPrepend = Array("-XX:+PrintCompilation", "-XX:+PrintGCDetails"))
@Fork(1)
@Threads(2)
class PaperPhilosopherCompetition[S <: Struct] {
  @Benchmark
  def eatOnce(comp: PaperCompetition[S], params: ThreadParams, work: Workload): Unit = {
    comp.table.eatRandomOnce(params.getThreadIndex, params.getThreadCount)
  }
}

@State(Scope.Benchmark)
class PaperCompetition[S <: Struct] extends BusyThreads {
  @Param(Array("dynamic","semi-static"))
  var dynamicity: String = _
  @Param(Array("16", "32"))
  var philosophers: Int = _
  var table: PaperPhilosophers[S] = _

  @Setup(Level.Trial)
  def printSystemStats() = {
    var assertions = false
    @inline def captureAssertionsEnabled = {
      assertions = true
      true
    }
    assert(captureAssertionsEnabled)
    println("Running on " + Runtime.getRuntime.availableProcessors() + " cores with assertions " + (if(assertions) "enabled." else "disabled."))
  }

  var stream: PrintStream = _
  @Setup(Level.Iteration)
  def setup(params: BenchmarkParams, work: Workload, engineParam: EngineParam[S]) = {
    table = new PaperPhilosophers(philosophers, engineParam.engine, dynamicity == "dynamic")

    if(engineParam.engineName == "fullmv") {
      SerializationGraphTracking.clear()
      stream = new PrintStream(new FileOutputStream(s"fullmv-sgt-contention-$philosophers-${params.getThreads}-${System.currentTimeMillis()}.txt"))
    }
  }

  @TearDown(Level.Iteration)
  def writeLockContentionTimer(): Unit = {
      if(stream != null) {
        SerializationGraphTracking.printStatistics(stream)
        stream.close()
    }
  }
}


