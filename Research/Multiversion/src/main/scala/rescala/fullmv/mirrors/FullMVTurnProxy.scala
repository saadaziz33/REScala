package rescala.fullmv.mirrors

import rescala.fullmv.{FullMVTurn, TransactionSpanningTreeNode, TurnPhase}

trait FullMVTurnProxy {
  def addRemoteBranch(forPhase: TurnPhase.Type): Unit
  def asyncRemoteBranchComplete(forPhase: TurnPhase.Type): Unit

  def acquirePhaseLockAndGetEstablishmentBundle(): (TurnPhase.Type, TransactionSpanningTreeNode[FullMVTurn])
  def addPredecessorAndReleasePhaseLock(predecessorSpanningTree: TransactionSpanningTreeNode[FullMVTurn]): Unit
  def asyncReleasePhaseLock(): Unit

  def maybeNewReachableSubtree(attachBelow: FullMVTurn, spanningSubTreeRoot: TransactionSpanningTreeNode[FullMVTurn]): Unit
  def newSuccessor(successor: FullMVTurn): Unit
}
