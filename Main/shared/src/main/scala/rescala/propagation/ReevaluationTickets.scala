package rescala.propagation

import rescala.graph._

trait ReevaluationTicket[R] {
  val turn: Turn[R]
}

trait StaticReevaluationTicket[R] extends ReevaluationTicket[R] {
  def regRead[A](accessibleNode: AccessibleNode[A, _, R]): A = {
    accessibleNode.struct.regRead(turn)
  }
}

class DynamicReevaluationTicket[R](override val turn: Turn[R], val issuer: Reactive[R]) extends ReevaluationTicket[R] {
  private[rescala] var collectedDependencies: Set[Reactive[R]] = Set.empty

  def depend[A](accessibleNode: AccessibleNode[A, _, R]): A = {
    if(issuer.incoming.contains(accessibleNode) || collectedDependencies.contains(accessibleNode)) {
      accessibleNode.struct.regRead(turn)
    } else {
      collectedDependencies += accessibleNode
      accessibleNode.struct.discover(turn, issuer)
    }
  }
}
