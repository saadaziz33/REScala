package rescala.fullmv.tasks

import java.util.concurrent.locks.LockSupport

import rescala.core.{Pulse, ReSource, Reactive, ReevaluationResult}
import rescala.fullmv.NotificationResultAction.{Glitched, ReevOutResult}
import rescala.fullmv.NotificationResultAction.NotificationOutAndSuccessorOperation.{FollowFraming, NextReevaluation, NoSuccessor}
import rescala.fullmv._

case class Reevaluation(override val turn: FullMVTurn, override val node: Reactive[FullMVStruct]) extends RegularReevaluationHandling {
  override def doCompute(): Unit = doReevaluation()
}

trait RegularReevaluationHandling extends ReevaluationHandling[Reactive[FullMVStruct]] {
  override val node: Reactive[FullMVStruct]
  def doReevaluation(): Unit = {
    assert(Thread.currentThread() == turn.userlandThread, s"$this on different thread ${Thread.currentThread().getName}")
    assert(turn.phase == TurnPhase.Executing, s"$turn cannot reevaluate (requires executing phase")
    val res: ReevaluationResult[node.Value, FullMVStruct] = try {
      node.reevaluate(turn, node.state.reevIn(turn), node.state.incomings)
    } catch {
      case exception: Throwable =>
        System.err.println(s"[FullMV Error] Reevaluation of $node failed with ${exception.getClass.getName}: ${exception.getMessage}; Completing reevaluation as NoChange.")
        exception.printStackTrace()
        ReevaluationResult.Static[Nothing, FullMVStruct](Pulse.NoChange, node.state.incomings).asInstanceOf[ReevaluationResult[node.Value, FullMVStruct]]
    }
    res.commitDependencyDiff(turn, node)
    processReevaluationResult(res)
  }

  override def createReevaluation(succTxn: FullMVTurn) = Reevaluation(succTxn, node)
}

case class SourceReevaluation(override val turn: FullMVTurn, override val node: ReSource[FullMVStruct]) extends SourceReevaluationHandling {
  override def doCompute(): Unit = doReevaluation()
}

trait SourceReevaluationHandling extends ReevaluationHandling[ReSource[FullMVStruct]] {
  def doReevaluation(): Unit = {
    assert(Thread.currentThread() == turn.userlandThread, s"$this on different thread ${Thread.currentThread().getName}")
    assert(turn.phase == TurnPhase.Executing, s"$turn cannot source-reevaluate (requires executing phase")
    val ic = turn.initialChanges(node)
    assert(ic.r == node, s"$turn initial change map broken?")
    val res = ic.v(ic.r.state.reevIn(turn)).asInstanceOf[ReevaluationResult[node.Value, FullMVStruct]]
    processReevaluationResult(res)
  }

  override def createReevaluation(succTxn: FullMVTurn): FullMVAction = SourceReevaluation(succTxn, node)
}

trait ReevaluationHandling[N <: ReSource[FullMVStruct]] extends FullMVAction {
  val node: N
  def createReevaluation(succTxn: FullMVTurn): FullMVAction
  def doReevaluation(): Unit

  def processReevaluationResult(res: ReevaluationResult[node.Value, FullMVStruct]): Unit = {
    if(res.valueChanged) {
      processReevOutResult(node.state.reevOut(turn, if (res.valueChanged) Some(res.value) else None), changed = true)
    } else {
      processReevOutResult(node.state.reevOut(turn, None), changed = false)
    }
  }

  def processReevOutResult(outAndSucc: ReevOutResult[FullMVTurn, Reactive[FullMVStruct]], changed: Boolean): Unit = {
    if(FullMVEngine.DEBUG) println(s"[${Thread.currentThread().getName}] Reevaluation($turn,$node) => ${if(changed) "changed" else "unchanged"} $outAndSucc")
    outAndSucc match {
      case Glitched =>
      // do nothing, reevaluation will be repeated at a later point
      case NoSuccessor(out) =>
        for(dep <- out) turn.pushLocalTask(Notification(turn, dep, changed))
      case FollowFraming(out, succTxn) =>
        for(dep <- out) turn.pushLocalTask(NotificationWithFollowFrame(turn, dep, changed, succTxn))
      case NextReevaluation(out, succTxn) =>
        for(dep <- out) turn.pushLocalTask(NotificationWithFollowFrame(turn, dep, changed, succTxn))
        succTxn.pushExternalTask(createReevaluation(succTxn))
        LockSupport.unpark(succTxn.userlandThread)
    }
  }
}
