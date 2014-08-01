package philosophers

import rescala._
import scala.collection.immutable.IndexedSeq
import scala.concurrent._
import scala.concurrent.stm._
import scala.concurrent.duration._
import scala.concurrent.Await
import java.util.concurrent.TimeoutException
import scala.concurrent.ExecutionContext.Implicits._
import scala.util.Random


// ===================== FORK IMPLEMENTATION =====================
case class Fork(id: Int) {
  // input
  val in = Var[Set[Signal[Option[Philosopher]]]](Set())
  def addPhilosopher(phil: Philosopher)(implicit tx: InTxn) {
    in() = in.get + phil.request
  }

  // intermediate
  val requests = SignalSynt[Set[Philosopher]](){s => in(s).map(_.apply(s)).flatten }

  // output
  val owner: Signal[Option[Philosopher]] = requests.map(_.headOption)
  val isOccupied = owner.map(_.isDefined)
}

// ===================== PHILOSOPHER IMPLEMENTATION =====================
object Philosopher {
  // function: a philosopher is eating whenever she owns all connected forks
  val calculateEating: (Philosopher, Iterable[Option[Philosopher]]) => Boolean =
    (philosopher, forks) => forks.find(_ != Some(philosopher)).isEmpty
}

case class Philosopher(id: Int) {
  // input
  val tryingToEat = Var(false)

  // intermediate
  val request = tryingToEat.map {
    case false => None
    case true => Some(this)
  }

  // connect input to forks
  val forks = Var(Set[Fork]())
  def addFork(fork: Fork)(implicit tx: InTxn) = {
    fork.addPhilosopher(this)
    forks() = forks.get + fork
  }

  // connect output from forks
  val owners = SignalSynt[Set[Option[Philosopher]]](){s => forks(s).map(_.owner(s))}
  val isEating = SignalSynt[Boolean]() {s => Philosopher.calculateEating(this, owners(s)) }

  // behavior
  def eatOnce() = {
    atomic { tx =>
      // await free forks
      if (forks.get.exists(_.isOccupied.get)) {
        retry(tx)
      }
      Txn.afterRollback(_ => println(this + " suffered fork acquisition failure!"))(tx)

      // try to take forks
      tryingToEat() = true
    }

    // release forks
    atomic { tx =>
      tryingToEat() = false
    }
  }
}

object Philosophers extends App {
  // ===================== PARAMETER PARSING =====================
  val sizeOfTable = if (args.length < 1) {
    println("Using default table size 3. Supply an integer number as first program argument to customize.");
    3
  } else {
    Integer.parseInt(args(0))
  }

  // ===================== TABLE SETUP =====================
  println("Setting up table with " + sizeOfTable + " forks and philosophers.")

  // create forks
  val fork = for (i <- 0 until sizeOfTable) yield new Fork(i)
  // create and run philosophers
  val philosophers = for (i <- 0 until sizeOfTable) yield new Philosopher(i)
  // connect philosophers with forks
  philosophers.foreach { phil =>
    atomic { implicit tx =>
      phil.addFork(fork(phil.id))
      phil.addFork(fork((phil.id + 1) % 3))
    }
  }

  // ===================== OBSERVATION SETUP =====================
  def log(msg: String) = {
    println("[" + Thread.currentThread().getName + " @ " + System.currentTimeMillis() + "] " + msg)
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
  //    _.isEating.observe { value =>
  //        log(this + " is " + (if (value) "now" else "no longer") + " eating");
  //      }
  //  }

  // ---- table state observation ----
  atomic { implicit tx =>
    val eatingStates: IndexedSeq[Signal[(Boolean, Philosopher)]] = philosophers.map(p => p.isEating.map(_ -> p))
    val allEating = SignalSynt[IndexedSeq[Philosopher]]() { s => eatingStates.map(_.apply(s)).collect { case (true, phil) => phil } }
    allEating.changed += (eating => log("Now eating: " + eating))
  }

  // ===================== STARTUP =====================
  // start simulation
  @volatile private var killed = false
  println("Starting simulation. Press <Enter> to terminate!")
  println(philosophers)
  val threads = philosophers.map { phil =>
    phil -> {
      val thread = new Thread {
        override def run(): Unit = {
          Thread.currentThread().setName("p" + phil.id)
          println(phil + ": using " + phil.forks.get + " on thread" + Thread.currentThread().getName)
          while (!killed) {
            //Thread.sleep(Random.nextInt(500))
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
      thread.join()
      log(phil + " terminated.")
    } catch {
      case te: TimeoutException => log(phil + " failed to terminate!")
    }
  }
}
