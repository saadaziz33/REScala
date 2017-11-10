package rescala.fullmv.tasks

import java.util.concurrent.RecursiveAction
import java.util.concurrent.locks.LockSupport

import rescala.fullmv.{FullMVEngine, FullMVTurn}

trait FullMVAction extends RecursiveAction {
  val turn: FullMVTurn

  def doCompute(): Traversable[FullMVAction]

  final override def compute(): Unit = {
    val forks = try{
     doCompute()
    } catch {
      case e: Throwable =>
        new Exception(s"Task $this failed on Thread ${Thread.currentThread().getName}.", e).printStackTrace()
        Traversable.empty
    }
    // this implementation will unpark threads multiple times, or unpark our own thread, if the returned collection
    // isn't ordered by turns with all our own thread's turn's tasks first. current implementations adhere to this restriction.
    // if future implementations don't adhere to it, then this isn't harmful though, just sub-optimal.
    forks.foldLeft(turn) {(lastUnparked, fork) =>
      val forkTurn = fork.turn
      forkTurn.taskQueue.offer(fork)
      if(forkTurn != lastUnparked) {
        if (FullMVEngine.DEBUG) println(s"[${Thread.currentThread().getName}] $this unparking $forkTurn for task transfer of $fork.")
        LockSupport.unpark(forkTurn.userlandThread)
        forkTurn
      } else {
        lastUnparked
      }
    }
  }
}
