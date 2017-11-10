package rescala.fullmv

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.{LockSupport, ReentrantLock}

import rescala.fullmv.TurnPhase.Type

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer

class FullMVTurnImpl(override val engine: FullMVEngine, val userlandThread: Thread) extends FullMVTurn {
  // counts the sum of in-flight notifications, in-progress reevaluations.
  var activeBranches = new AtomicInteger(0)

  val phaseLock = new ReentrantLock()
  object phaseParking
  @volatile var phase: TurnPhase.Type = TurnPhase.Initialized

  val successorsIncludingSelf: ArrayBuffer[FullMVTurn] = ArrayBuffer(this) // this is implicitly a set
  val selfNode = new MutableTransactionSpanningTreeNode[FullMVTurn](this)
  @volatile var predecessorSpanningTreeNodes: Map[FullMVTurn, MutableTransactionSpanningTreeNode[FullMVTurn]] = Map(this -> selfNode)

  override def asyncRemoteBranchComplete(forPhase: Type): Unit = {
    if(FullMVEngine.DEBUG) println(s"[${Thread.currentThread().getName}] $this branch on some remote completed")
    activeBranchDifferential(forPhase, -1)
  }

  def activeBranchDifferential(forState: TurnPhase.Type, differential: Int): Unit = {
    assert(phase == forState, s"$this received branch differential for wrong state ${TurnPhase.toString(forState)}")
    assert(differential != 0, s"$this received 0 branch diff")
    assert(activeBranches.get + differential >= 0, s"$this received branch diff into negative count")
    val remaining = activeBranches.addAndGet(differential)
    if(remaining == 0) {
      LockSupport.unpark(userlandThread)
    }
  }

  override def newBranchFromRemote(forPhase: Type): Unit = {
    assert(phase == forPhase, s"$this received branch differential for wrong state ${TurnPhase.toString(forPhase)}")
    if(FullMVEngine.DEBUG) println(s"[${Thread.currentThread().getName}] $this new branch on remote is actually loop-back to local")
    // technically, move one remote branch to a local branch, but as we don't count these separately, currently doing nothing.
  }

  override def addRemoteBranch(forPhase: TurnPhase.Type): Unit = {
    assert(phase == forPhase, s"$this received branch differential for wrong state ${TurnPhase.toString(forPhase)}")
    if(FullMVEngine.DEBUG) println(s"[${Thread.currentThread().getName}] $this new branch on some remote")
    activeBranches.getAndIncrement()
  }

  //========================================================Local State Control============================================================

  def awaitAndSwitchPhase(newPhase: TurnPhase.Type): Unit = {
    assert(newPhase > this.phase, s"$this cannot progress backwards to phase $newPhase.")
    @tailrec def awaitAndAtomicCasPhase(): Unit = {
      awaitBranchCountZero()
      val compare = awaitAllPredecessorsPhase(newPhase)
      phaseLock.lock()
      val success = try {
        if (activeBranches.get == 0 && (predecessorSpanningTreeNodes eq compare)) {
          phaseParking.synchronized {
            this.phase = newPhase
            phaseParking.notifyAll()
          }
          if (newPhase == TurnPhase.Completed) {
            predecessorSpanningTreeNodes = Map.empty
            selfNode.children = Set.empty
          }
          if (FullMVEngine.DEBUG) println(s"[${Thread.currentThread().getName}] $this switched phase.")
          true
        } else {
          false
        }
      } finally {
        phaseLock.unlock()
      }
      if(!success) awaitAndAtomicCasPhase()
    }
    awaitAndAtomicCasPhase()
  }


  private def awaitBranchCountZero(): Unit = {
    while (activeBranches.get > 0) {
      LockSupport.park(this)
    }
  }

  private def awaitAllPredecessorsPhase(atLeast: TurnPhase.Type) = {
    val preds = predecessorSpanningTreeNodes
    if (FullMVEngine.DEBUG) println(s"[${Thread.currentThread().getName}] $this awaiting phase $atLeast+ on predecessors ${preds.keySet - this}")
    preds.keySet.foreach { waitFor =>
      if(waitFor != this) waitFor.awaitPhase(atLeast)
    }
    preds
  }


  //========================================================Remote State Control============================================================

  override def awaitPhase(atLeast: TurnPhase.Type): Unit = phaseParking.synchronized {
    while(phase < atLeast) {
      phaseParking.wait()
    }
  }

  //========================================================Ordering Search and Establishment Interface============================================================

  def isTransitivePredecessor(txn: FullMVTurn): Boolean = {
    predecessorSpanningTreeNodes.contains(txn)
  }


  override def acquirePhaseLockAndGetEstablishmentBundle(): (TurnPhase.Type, TransactionSpanningTreeNode[FullMVTurn]) = {
    // TODO think about how and where to try{}finally{unlock()} this..
    phaseLock.lock()
    (phase, selfNode)
  }

  def addPredecessorAndReleasePhaseLock(predecessorSpanningTree: TransactionSpanningTreeNode[FullMVTurn]): Unit = {
    @inline def predecessor = predecessorSpanningTree.txn
    assert(!isTransitivePredecessor(predecessor), s"attempted to establish already existing predecessor relation $predecessor -> $this")
    if(FullMVEngine.DEBUG) println(s"[${Thread.currentThread().getName}] $this new predecessor $predecessor.")
    for(succ <- successorsIncludingSelf) succ.maybeNewReachableSubtree(this, predecessorSpanningTree)
    phaseLock.unlock()
  }

  override def maybeNewReachableSubtree(attachBelow: FullMVTurn, spanningSubTreeRoot: TransactionSpanningTreeNode[FullMVTurn]): Unit = {
    if (!isTransitivePredecessor(spanningSubTreeRoot.txn)) {
      copySubTreeRootAndAssessChildren(attachBelow, spanningSubTreeRoot)
    }
  }

  private def copySubTreeRootAndAssessChildren(attachBelow: FullMVTurn, spanningSubTreeRoot: TransactionSpanningTreeNode[FullMVTurn]): Unit = {
    val newTransitivePredecessor = spanningSubTreeRoot.txn
    newTransitivePredecessor.newSuccessor(this)
    val copiedSpanningTreeNode = new MutableTransactionSpanningTreeNode(newTransitivePredecessor)
    predecessorSpanningTreeNodes += newTransitivePredecessor -> copiedSpanningTreeNode
    predecessorSpanningTreeNodes(attachBelow).children += copiedSpanningTreeNode

    for (child <- spanningSubTreeRoot.children) {
      if(!isTransitivePredecessor(child.txn)) {
        copySubTreeRootAndAssessChildren(newTransitivePredecessor, child)
      }
    }
  }

  override def newSuccessor(successor: FullMVTurn): Unit = {
    successorsIncludingSelf += successor
  }

  override def asyncReleasePhaseLock(): Unit = phaseLock.unlock()

  //========================================================ToString============================================================

  override def toString: String = s"FullMVTurn($hashCode, ${TurnPhase.toString(phase)}${if(activeBranches.get != 0) s"(${activeBranches.get})" else ""})"
}
