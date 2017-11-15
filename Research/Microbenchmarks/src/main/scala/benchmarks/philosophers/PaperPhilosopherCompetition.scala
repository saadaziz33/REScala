package benchmarks.philosophers

import java.util.concurrent.{CountDownLatch, TimeUnit}

import benchmarks.{EngineParam, Workload}
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.{BenchmarkParams, Blackhole, ThreadParams}
import rescala.core.Struct

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Fork(value=1, jvmArgsPrepend = Array("-XX:+PrintCompilation", "-XX:+PrintGCDetails"))
@Threads(2)
class PaperPhilosopherCompetition[S <: Struct] {
  @Benchmark
  def eatOnce(comp: PaperCompetition[S], params: ThreadParams, work: Workload): Unit = {
    comp.table.eatRandomOnce(params.getThreadIndex, params.getThreadCount)
  }
}

@State(Scope.Benchmark)
class PaperCompetition[S <: Struct] {
  @Param(Array("dynamic","semi-static"))
  var dynamicity: String = _
  @Param(Array("16", "32"))
  var philosophers: Int = _
  var table: PaperPhilosophers[S] = _

  @Param(Array("false"))
  var runBusyThreads: Boolean = _

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

  @Setup(Level.Iteration)
  def setup(params: BenchmarkParams, work: Workload, engineParam: EngineParam[S]) = {
    table = new PaperPhilosophers(philosophers, engineParam.engine, dynamicity == "dynamic")
  }

  @volatile var running: Boolean = false
  var threads: Array[Thread] = _
  @Setup(Level.Iteration)
  def bootBusyThreads(params: BenchmarkParams) = {
    if(runBusyThreads) {
      running = true
      val numProcs = Runtime.getRuntime.availableProcessors()
      val idleProcs = numProcs - params.getThreads
      val startLatch = new CountDownLatch(idleProcs)
      threads = Array.tabulate(idleProcs) { i =>
        val t = new Thread(s"busy-idler-$i") {
          override def run(): Unit = {
            startLatch.countDown()
            while (running) Blackhole.consumeCPU(1000L)
          }
        }
        t.start()
        t
      }
      println(s"starting $idleProcs busy threads...")
      if (!startLatch.await(1000, TimeUnit.MILLISECONDS)) {
        println(startLatch.getCount + " busy threads failed to start")
      }
    }
  }
  @TearDown(Level.Iteration)
  def stopBusyThreads() = {
    if(runBusyThreads) {
      println("stopping busy threads...")
      val timeout = System.currentTimeMillis() + 1000
      running = false
      for (t <- threads) {
        t.join(timeout - System.currentTimeMillis())
        if (t.isAlive) println(t.getName + " did not terminate!")
      }
    }
  }
}
