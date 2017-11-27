package rescala.fullmv.tasks

import rescala.core.{ReSource, Reactive}
import rescala.fullmv.NotificationResultAction._
import rescala.fullmv._

trait NotificationAction[N <: ReSource[FullMVStruct]] extends ReevOutResultHandling[N] {
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
        turn.offer(createReevaluation(turn))
      case outAndSucc: NotificationOutAndSuccessorOperation[FullMVTurn, Reactive[FullMVStruct]] =>
        processReevOutResult(outAndSucc, changed = false)
    }
  }

  def deliverNotification(): NotificationResultAction[FullMVTurn, Reactive[FullMVStruct]]
}

case class SourceNotification(override val turn: FullMVTurn, override val node: ReSource[FullMVStruct], override val changed: Boolean) extends NotificationAction[ReSource[FullMVStruct]] {
  override def deliverNotification(): NotificationResultAction[FullMVTurn, Reactive[FullMVStruct]] = node.state.notify(turn, changed)
  override def createReevaluation(succTxn: FullMVTurn) = SourceReevaluation(succTxn, node)
}

case class Notification(override val turn: FullMVTurn, override val node: Reactive[FullMVStruct], override val changed: Boolean) extends NotificationAction[Reactive[FullMVStruct]] {
  override def deliverNotification(): NotificationResultAction[FullMVTurn, Reactive[FullMVStruct]] = node.state.notify(turn, changed)
  override def createReevaluation(succTxn: FullMVTurn) = Reevaluation(succTxn, node)
}
case class NotificationWithFollowFrame(override val turn: FullMVTurn, override val node: Reactive[FullMVStruct], override val changed: Boolean, followFrame: FullMVTurn) extends NotificationAction[Reactive[FullMVStruct]] {
  override def deliverNotification(): NotificationResultAction[FullMVTurn, Reactive[FullMVStruct]] = node.state.notifyFollowFrame(turn, changed, followFrame)
  override def createReevaluation(succTxn: FullMVTurn) = Reevaluation(succTxn, node)
}
