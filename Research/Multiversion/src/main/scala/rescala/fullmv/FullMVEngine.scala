package rescala.fullmv

import java.util.concurrent.Executor

import rescala.core.{EngineImpl, ReSourciV}
import rescala.fullmv.tasks._

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import scala.concurrent.duration._
import scala.util.Try

class FullMVEngine(val timeout: Duration, val name: String) extends EngineImpl[FullMVStruct, FullMVTurn] {
  def newTurn(): FullMVTurn = new FullMVTurn(this, Thread.currentThread())
  val dummy: FullMVTurn = {
    val dummy = new FullMVTurn(this, null)
    dummy.awaitAndSwitchPhase(TurnPhase.Completed)
    dummy
  }

  override private[rescala] def singleNow[A](reactive: ReSourciV[A, FullMVStruct]) = reactive.state.latestValue

  override private[rescala] def executeTurn[R](declaredWrites: Traversable[ReSource], admissionPhase: (AdmissionTicket) => R): R = {
    val turn = newTurn()
    withTurn(turn) {
      val setWrites = declaredWrites.toSet // this *should* be part of the interface..
      if (setWrites.nonEmpty) {
        // framing phase
        turn.awaitAndSwitchPhase(TurnPhase.Framing)
        for (i <- setWrites) turn.offer(Framing(turn, i))
      }

      turn.awaitAndSwitchPhase(TurnPhase.Executing)

      // admission phase
      val admissionTicket = turn.makeAdmissionPhaseTicket()
      val admissionResult = Try { admissionPhase(admissionTicket) }
      if (FullMVEngine.DEBUG) admissionResult match {
        case scala.util.Failure(e) => e.printStackTrace()
        case _ =>
      }
      assert(turn.taskQueue.isEmpty, s"Admission phase left ${turn.taskQueue.size()} active branches.")

      // propagation phase
      if (setWrites.nonEmpty) {
        turn.initialChanges = admissionTicket.initialChanges
        for(write <- setWrites) {
          turn.offer(SourceNotification(turn, write, admissionResult.isSuccess && admissionTicket.initialChanges.contains(write)))
        }
      }

      // propagation completion
      if (FullMVEngine.SEPARATE_WRAPUP_PHASE) turn.awaitAndSwitchPhase(TurnPhase.WrapUp)

      // wrap-up "phase" (executes in parallel with propagation)
      admissionResult.map { i => admissionTicket.wrapUp(turn.makeWrapUpPhaseTicket()); i }

      if (FullMVEngine.SEPARATE_WRAPUP_PHASE) assert(turn.taskQueue.isEmpty, s"WrapUp phase left ${turn.taskQueue.size()} active branches.")

      // turn completion
      turn.awaitAndSwitchPhase(TurnPhase.Completed)

      // result
      admissionResult.get
    }
  }

  override def toString: String = "Host " + name
}

object FullMVEngine {
  val SEPARATE_WRAPUP_PHASE = false
  val DEBUG = false

  val default = new FullMVEngine(10.seconds, "default")

  val notWorthToMoveToTaskpool: ExecutionContextExecutor = ExecutionContext.fromExecutor(new Executor{
    override def execute(command: Runnable): Unit = command.run()
  })
}
