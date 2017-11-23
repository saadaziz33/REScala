package rescala.fullmv.tasks

import java.util.concurrent.locks.LockSupport

import rescala.core.{Pulse, Reactive, ReevaluationResult}
import rescala.fullmv.NotificationResultAction.{Glitched, ReevOutResult}
import rescala.fullmv.NotificationResultAction.NotificationOutAndSuccessorOperation.{FollowFraming, NextReevaluation, NoSuccessor}
import rescala.fullmv._

case class Reevaluation(turn: FullMVTurn, node: Reactive[FullMVStruct]) extends FullMVAction {
  override def doCompute(): Unit = {
    assert(turn.phase == TurnPhase.Executing, s"$this cannot reevaluate (requires executing phase")
    val res: ReevaluationResult[node.Value, FullMVStruct] = try {
      node.reevaluate(turn, node.state.reevIn(turn), node.state.incomings)
    } catch {
      case exception: Throwable =>
        System.err.println(s"[FullMV Error] Reevaluation of $node failed with ${exception.getClass.getName}: ${exception.getMessage}; Completing reevaluation as NoChange.")
        exception.printStackTrace()
        ReevaluationResult.Static[Nothing, FullMVStruct](Pulse.NoChange, node.state.incomings).asInstanceOf[ReevaluationResult[node.Value, FullMVStruct]]
    }
    res.commitDependencyDiff(turn, node)
    if(res.valueChanged) {
      Reevaluation.processReevaluationResult(node, turn, node.state.reevOut(turn, if (res.valueChanged) Some(res.value) else None), changed = true)
    } else {
      Reevaluation.processReevaluationResult(node, turn, node.state.reevOut(turn, None), changed = false)
    }
  }
}

object Reevaluation {
  def processReevaluationResult(node: Reactive[FullMVStruct], turn: FullMVTurn, outAndSucc: ReevOutResult[FullMVTurn, Reactive[FullMVStruct]], changed: Boolean): Unit = {
    if(FullMVEngine.DEBUG) println(s"[${Thread.currentThread().getName}] Reevaluation($turn,$node) => ${if(changed) "changed" else "unchanged"} $outAndSucc")
    outAndSucc match {
      case Glitched =>
        // do nothing, reevaluation will be repeated at a later point
      case NoSuccessor(out) =>
        for(dep <- out) turn.taskQueue.offer(Notification(turn, dep, changed))
      case FollowFraming(out, succTxn) =>
        for(dep <- out) turn.taskQueue.offer(NotificationWithFollowFrame(turn, dep, changed, succTxn))
      case NextReevaluation(out, succTxn) =>
        for(dep <- out) turn.taskQueue.offer(NotificationWithFollowFrame(turn, dep, changed, succTxn))
        succTxn.taskQueue.offer(Reevaluation(succTxn, node))
        LockSupport.unpark(succTxn.userlandThread)
    }
  }
}
