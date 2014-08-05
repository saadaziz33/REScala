package philosophers

import java.util.concurrent.TimeoutException

import rescala._

import scala.collection.immutable.IndexedSeq
import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.{Await, _}
import scala.concurrent.duration._
import scala.concurrent.stm._
import scala.util.Random

object RetryFork {
  case class MultipleRequestsException(fork: RetryFork) extends RuntimeException("Multiple Requests for " + fork)
}
// ===================== FORK IMPLEMENTATION =====================
case class RetryFork(id: Int) {
  // input
  val in = Var[Set[Signal[Option[RetryPhilosopher]]]](Set())
  def addPhilosopher(phil: RetryPhilosopher)(implicit tx: InTxn): Unit = {
    in() = in.get + phil.request
  }

  // intermediate
  val requests = SignalSynt[Set[RetryPhilosopher]](){ s => in(s).map(_.apply(s)).flatten }

  // output
  val owner = requests.map { (requests) =>
    if (requests.size > 1) throw new RetryFork.MultipleRequestsException(this)
    requests.headOption
  }
  val isOccupied = owner.map(_.isDefined)
}

// ===================== PHILOSOPHER IMPLEMENTATION =====================
object RetryPhilosopher {
  sealed trait State
  case object Thinking extends State
  case object Eating extends State
}

case class RetryPhilosopher(id: Int) {
  import RetryPhilosopher._

  // input
  val state: Var[State] = Var(Thinking)

  // auto-release forks whenever eating successfully
  state.changed.filter(_ == Eating) += { _ => state() = Thinking }

  // intermediate
  val request = state.map {
    case Thinking => None
    case Eating => Some(this)
  }

  // connect input to forks
  val forks = Var(Set[RetryFork]())
  def addFork(fork: RetryFork)(implicit tx: InTxn) = {
    fork.addPhilosopher(this)
    forks() = forks.get + fork
  }

  // behavior
  def eatOnce() = {
    // Variant 1: try new transaction until one succeeds
    while (try {
      RetryPhilosophers.log(state.get.toString)
      state() = Eating
      false
    } catch {
      case e: RetryFork.MultipleRequestsException =>
        RetryPhilosophers.log(s"Multiple request exception")
        true
    }) {}

    // Variant 2: rollback update transaction until it succeeds
    //    atomic { tx =>
    //      try {
    //        state << Eating;
    //      } catch {
    //        case e: RetryFork.MultipleRequestsException => retry(tx)
    //      }
    //    }
  }
}

object RetryPhilosophers extends App {
  // ===================== PARAMETER PARSING =====================
  val sizeOfTable = if (args.length < 1) {
    println("Using default table size 3. Supply an integer number as first program argument to customize.")
    3
  } else {
    Integer.parseInt(args(0))
  }

  // ===================== TABLE SETUP =====================
  println("Setting up table with " + sizeOfTable + " forks and philosophers.")

  // create forks
  val fork = for (i <- 0 until sizeOfTable) yield new RetryFork(i)
  // create and run philosophers
  val philosopher = for (i <- 0 until sizeOfTable) yield new RetryPhilosopher(i)
  // connect philosophers with forks
  philosopher.foreach { phil =>
    atomic { implicit tx =>
      phil.addFork(fork(phil.id))
      phil.addFork(fork((phil.id + 1) % 3))
    }
  }

  // ===================== OBSERVATION SETUP =====================
  def log(msg: String) = {
    println("[" + Thread.currentThread().getName() + " @ " + System.currentTimeMillis() + "] " + msg)
  }

  // ---- fork state observation ----
  //  fork.foreach(_.owner.observe { owner =>
  //    log(this + " now " + (owner match {
  //      case Some(x) => "owned by " + x
  //      case None => "free"
  //    }))
  //  })

  // ---- philosopher state observation ----
  //  philosopher.foreach {
  //    _.state.observe { value =>
  //        log(this + " is now " + value);
  //      }
  //  }

  // ---- table state observation ----
  atomic { implicit tx =>
    val eatingStates: IndexedSeq[Signal[Option[RetryPhilosopher]]] = philosopher.map(_.request)
    val allEating = SignalSynt[IndexedSeq[RetryPhilosopher]]() { s => eatingStates.flatMap(_.apply(s)) }
    allEating.changed.+=(eating => log("Now eating: " + eating))
  }

  // ===================== STARTUP =====================
  // start simulation
  @volatile private var killed = false
  println("Starting simulation. Press <Enter> to terminate!")
  val threads = philosopher.map { phil =>
    phil -> {
      val thread = new Thread {
        override def run(): Unit = {
          Thread.currentThread().setName("p" + phil.id)
          println(phil + ": using " + phil.forks.get + " on thread " + Thread.currentThread().getName)
          while (!killed) {
            phil.eatOnce()
          }
          log(phil + " dies.")
        }
      }
      thread.start()
      thread
    }

  }

  // ===================== SHUTDOWN =====================
  // wait for keyboard input
  System.in.read()

  // kill all philosophers
  log("Received Termination Signal, Terminating...")
  killed = true

  // collect forked threads to check termination
  threads.foreach {
    case (phil, thread) => try {
      import scala.language.postfixOps
      thread.join()
      log(phil + " terminated.")
    } catch {
      case te: TimeoutException => log(phil + " failed to terminate!")
    }
  }
}
