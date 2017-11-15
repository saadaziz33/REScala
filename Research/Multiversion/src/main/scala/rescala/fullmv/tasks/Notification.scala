package rescala.fullmv.tasks

import rescala.core.Reactive
import rescala.fullmv.NotificationResultAction._
import rescala.fullmv._

trait NotificationAction extends FullMVAction {
  val node: Reactive[FullMVStruct]
  val changed: Boolean
  override def doCompute(): Unit = {
    val notificationResultAction = deliverNotification()
    if(FullMVEngine.DEBUG) println(s"[${Thread.currentThread().getName}] $this => $notificationResultAction")
    processNotificationResult(notificationResultAction)
  }

  def processNotificationResult(notificationResultAction: NotificationResultAction[FullMVTurn, Reactive[FullMVStruct]]): Unit = {
    notificationResultAction match {
      case GlitchFreeReadyButQueued =>
        Traversable.empty
      case ResolvedNonFirstFrameToUnchanged =>
        Traversable.empty
      case NotGlitchFreeReady =>
        Traversable.empty
      case GlitchFreeReady =>
        turn.taskQueue.offer(Reevaluation(turn, node))
      case outAndSucc: NotificationOutAndSuccessorOperation[FullMVTurn, Reactive[FullMVStruct]] =>
        Reevaluation.processReevaluationResult(node, turn, outAndSucc, changed = false)
    }
  }

  def deliverNotification(): NotificationResultAction[FullMVTurn, Reactive[FullMVStruct]]
}

case class Notification(turn: FullMVTurn, node: Reactive[FullMVStruct], changed: Boolean) extends NotificationAction {
  override def deliverNotification(): NotificationResultAction[FullMVTurn, Reactive[FullMVStruct]] = node.state.notify(turn, changed)
}
case class NotificationWithFollowFrame(turn: FullMVTurn, node: Reactive[FullMVStruct], changed: Boolean, followFrame: FullMVTurn) extends NotificationAction {
  override def deliverNotification(): NotificationResultAction[FullMVTurn, Reactive[FullMVStruct]] = node.state.notifyFollowFrame(turn, changed, followFrame)
}
