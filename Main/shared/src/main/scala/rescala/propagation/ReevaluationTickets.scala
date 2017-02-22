package rescala.propagation

import rescala.graph._

trait ReevaluationTicket[R] {
  val turn: Turn[R]
}

class StaticReevaluationTicket[R](override val turn: Turn[R]) extends ReevaluationTicket[R] {
  def regRead[A](accessibleNode: AccessibleNode[A, _, R]): A = {
    accessibleNode.struct.regRead(turn)
  }
}

class DynamicReevaluationTicket[R](override val turn: Turn[R], val issuer: Reactive[R], incoming: Set[AccessibleNode[_, _, R]]) extends ReevaluationTicket[R] {
  private[rescala] var collectedDependencies: Set[AccessibleNode[_, _, R]] = Set.empty
  private[rescala] var incomingsChanged: Boolean = false

  def depend[A](accessibleNode: AccessibleNode[A, _, R]): A = {
    if(issuer.incoming.contains(accessibleNode) || collectedDependencies.contains(accessibleNode)) {
      accessibleNode.struct.regRead(turn)
    } else {
      collectedDependencies += accessibleNode
      incomingsChanged = true
      accessibleNode.struct.discover(turn, issuer)
    }
  }

  private[rescala] def dropsAfterReevaluation(): Boolean = {
    val drops = incoming -- collectedDependencies
    for(drop <- drops) drop.struct.drop(turn, this)
    incomingsChanged ||= !drops.isEmpty
  }
}
