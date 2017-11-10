package rescala.fullmv

import java.util.concurrent.locks.{LockSupport, ReentrantLock}
import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue}
import java.util.function.BiConsumer

import rescala.core._
import rescala.fullmv.NotificationResultAction.NotificationOutAndSuccessorOperation.NoSuccessor
import rescala.fullmv.mirrors.FullMVTurnProxy
import rescala.fullmv.tasks.{FullMVAction, Notification, Reevaluation}

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer

class FullMVTurn(val engine: FullMVEngine, val userlandThread: Thread) extends TurnImpl[FullMVStruct] with FullMVTurnProxy {
  val taskQueue = new ConcurrentLinkedQueue[FullMVAction]()
  val waiters = new ConcurrentHashMap[Thread, TurnPhase.Type]()

  val phaseLock = new ReentrantLock()
  @volatile var phase: TurnPhase.Type = TurnPhase.Initialized

  val successorsIncludingSelf: ArrayBuffer[FullMVTurn] = ArrayBuffer(this) // this is implicitly a set
  val selfNode = new MutableTransactionSpanningTreeNode[FullMVTurn](this)
  @volatile var predecessorSpanningTreeNodes: Map[FullMVTurn, MutableTransactionSpanningTreeNode[FullMVTurn]] = Map(this -> selfNode)

  //========================================================Local State Control============================================================

  def awaitAndSwitchPhase(newPhase: TurnPhase.Type): Unit = {
    assert(newPhase > this.phase, s"$this cannot progress backwards to phase $newPhase.")
    @tailrec def awaitAndAtomicCasPhase(): Unit = {
      awaitBranchCountZero()
      val compare = predecessorSpanningTreeNodes
      compare.find{ case (turn, _) => turn != this && turn.phase < newPhase } match {
        case Some((turn, _)) =>
          turn.waiters.put(this.userlandThread, newPhase)
          while(taskQueue.isEmpty && turn.phase < newPhase) {
            if (FullMVEngine.DEBUG) println(s"[${Thread.currentThread().getName}] $this parking for $turn.")
            LockSupport.park(turn)
            if (FullMVEngine.DEBUG) println(s"[${Thread.currentThread().getName}] $this unparked.")
          }
          turn.waiters.remove(this.userlandThread)
          awaitAndAtomicCasPhase()
        case None =>
          phaseLock.lock()
          val success = try {
            if (taskQueue.isEmpty && (predecessorSpanningTreeNodes eq compare)) {
              this.phase = newPhase
              waiters.forEach(new BiConsumer[Thread, TurnPhase.Type] {
                override def accept(t: Thread, u: TurnPhase.Type) = if (u <= newPhase) {
                  if (FullMVEngine.DEBUG) println(s"[${Thread.currentThread().getName}] $this phase switch unparking $t.")
                  LockSupport.unpark(t)
                }
              })
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
    }
    awaitAndAtomicCasPhase()
  }


  @tailrec private def awaitBranchCountZero(): Unit = {
    val head = taskQueue.poll()
    if(head != null) {
      assert(head.turn == this, s"$head in taskQueue of $this")
      head.compute()
      awaitBranchCountZero()
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

  override def toString: String = s"FullMVTurn($hashCode, ${TurnPhase.toString(phase)}${if(taskQueue.size() != 0) s"(${taskQueue.size()})" else ""})"

  //========================================================Scheduler Interface============================================================

  override def makeDerivedStructState[P](valuePersistency: ValuePersistency[P]): NodeVersionHistory[P, FullMVTurn, ReSource[FullMVStruct], Reactive[FullMVStruct]] = {
    val state = new NodeVersionHistory[P, FullMVTurn, ReSource[FullMVStruct], Reactive[FullMVStruct]](engine.dummy, valuePersistency)
    state.incrementFrame(this)
    state
  }

  override protected def makeSourceStructState[P](valuePersistency: ValuePersistency[P]): NodeVersionHistory[P, FullMVTurn, ReSource[FullMVStruct], Reactive[FullMVStruct]] = {
    val state = makeDerivedStructState(valuePersistency)
    val res = state.notify(this, changed = false)
    assert(res == NoSuccessor(Set.empty))
    state
  }

  override def ignite(reactive: Reactive[FullMVStruct], incoming: Set[ReSource[FullMVStruct]], ignitionRequiresReevaluation: Boolean): Unit = {
    if (FullMVEngine.DEBUG) println(s"[${Thread.currentThread().getName}] $this igniting $reactive on $incoming")
    incoming.foreach { discover =>
      discover.state.dynamicAfter(this) // TODO should we get rid of this?
      val (successorWrittenVersions, maybeFollowFrame) = discover.state.discover(this, reactive)
      reactive.state.retrofitSinkFrames(successorWrittenVersions, maybeFollowFrame, 1)
    }
    reactive.state.incomings = incoming
    // Execute this notification manually to be able to execute a resulting reevaluation immediately.
    // Subsequent reevaluations from retrofitting will be added to the global pool, but not awaited.
    // This matches the required behavior where the code that creates this reactive is expecting the initial
    // reevaluation (if one is required) to have been completed, but cannot access values from subsequent turns
    // and hence does not need to wait for those.
    val ignitionNotification = Notification(this, reactive, changed = ignitionRequiresReevaluation)
    val notificationResult = ignitionNotification.doCompute()
    if(notificationResult.nonEmpty) {
      assert(notificationResult.size == 1)
      assert(notificationResult.head == Reevaluation(this, reactive))
      val reevaluation = notificationResult.head.asInstanceOf[Reevaluation]
      val nextReev = reevaluation.doCompute()
      if(nextReev.nonEmpty) {
        assert(notificationResult.size == 1)
        assert(notificationResult.head.isInstanceOf[Reevaluation])
        val followReev = notificationResult.head.asInstanceOf[Reevaluation]
        if (FullMVEngine.DEBUG) println(s"[${Thread.currentThread().getName}] $this ignite $reactive reevaluated, delegating successor reevaluation for ${followReev.turn} to pool.")
        taskQueue.offer(followReev)
      } else {
        if (FullMVEngine.DEBUG) println(s"[${Thread.currentThread().getName}] $this ignite $reactive reevaluated, no successor reevaluation.")
      }
    } else {
      if (FullMVEngine.DEBUG) println(s"[${Thread.currentThread().getName}] $this ignite $reactive did not spawn reevaluation.")
    }
  }


  override private[rescala] def discover(node: ReSource[FullMVStruct], addOutgoing: Reactive[FullMVStruct]): Unit = {
    val (successorWrittenVersions, maybeFollowFrame) = node.state.discover(this, addOutgoing)
    if (FullMVEngine.DEBUG) println(s"[${Thread.currentThread().getName}] Reevaluation($this,$addOutgoing) discovering $node -> $addOutgoing re-queueing $successorWrittenVersions and re-framing $maybeFollowFrame")
    addOutgoing.state.retrofitSinkFrames(successorWrittenVersions, maybeFollowFrame, 1)
  }

  override private[rescala] def drop(node: ReSource[FullMVStruct], removeOutgoing: Reactive[FullMVStruct]): Unit = {
    val (successorWrittenVersions, maybeFollowFrame) = node.state.drop(this, removeOutgoing)
    if (FullMVEngine.DEBUG) println(s"[${Thread.currentThread().getName}] Reevaluation($this,$removeOutgoing) dropping $node -> $removeOutgoing de-queueing $successorWrittenVersions and de-framing $maybeFollowFrame")
    removeOutgoing.state.retrofitSinkFrames(successorWrittenVersions, maybeFollowFrame, -1)
  }

  override private[rescala] def writeIndeps(node: Reactive[FullMVStruct], indepsAfter: Set[ReSource[FullMVStruct]]): Unit = node.state.incomings = indepsAfter

  override private[rescala] def staticBefore[P](reactive: ReSourciV[P, FullMVStruct]) = reactive.state.staticBefore(this)
  override private[rescala] def staticAfter[P](reactive: ReSourciV[P, FullMVStruct]) = reactive.state.staticAfter(this)
  override private[rescala] def dynamicBefore[P](reactive: ReSourciV[P, FullMVStruct]) = reactive.state.dynamicBefore(this)
  override private[rescala] def dynamicAfter[P](reactive: ReSourciV[P, FullMVStruct]) = reactive.state.dynamicAfter(this)

  override def observe(f: () => Unit): Unit = f()
}
