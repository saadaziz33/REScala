package rescala.fullmv

import java.util.concurrent.locks.{LockSupport, ReentrantReadWriteLock}
import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue, ThreadLocalRandom}

import rescala.core._
import rescala.fullmv.NotificationResultAction.{GlitchFreeReady, NotGlitchFreeReady, NotificationOutAndSuccessorOperation}
import rescala.fullmv.NotificationResultAction.NotificationOutAndSuccessorOperation.{NextReevaluation, NoSuccessor}
import rescala.fullmv.tasks.{FullMVAction, Notification, Reevaluation}

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer

class FullMVTurn(val engine: FullMVEngine, val userlandThread: Thread) extends TurnImpl[FullMVStruct] {
  var initialChanges: collection.Map[ReSource[FullMVStruct], InitialChange[FullMVStruct]] = _

  val taskQueue = new ConcurrentLinkedQueue[FullMVAction]()
  def offer(action: FullMVAction): Unit = {
    assert(action.turn == this, s"$this received task of different turn: $action")
    taskQueue.offer(action)
  }
  val waiters = new ConcurrentHashMap[Thread, TurnPhase.Type]()

  private val hc = ThreadLocalRandom.current().nextInt()
  override def hashCode(): Int = hc

  val phaseLock = new ReentrantReadWriteLock()
  @volatile var phase: TurnPhase.Type = TurnPhase.Initialized

  val successorsIncludingSelf: ArrayBuffer[FullMVTurn] = ArrayBuffer(this) // this is implicitly a set
  @volatile var selfNode = new MutableTransactionSpanningTreeRoot[FullMVTurn](this) // this is also implicitly a set
  @volatile var predecessorSpanningTreeNodes: Map[FullMVTurn, TransactionSpanningTreeNode[FullMVTurn]] = Map(this -> selfNode)

  //========================================================Local State Control============================================================

//  var restarts: Int = 0

  private def awaitAndSwitchPhase(newPhase: TurnPhase.Type): Unit = {
    assert(newPhase > this.phase, s"$this cannot progress backwards to phase $newPhase.")
    @inline @tailrec def awaitAndSwitchPhase0(firstUnknownPredecessorIndex: Int, backOff: Long, registeredForWaiting: FullMVTurn): Unit = {
      val head = taskQueue.poll()
      if (head != null) {
        if (registeredForWaiting != null) {
          registeredForWaiting.waiters.remove(this.userlandThread)
//          restarts += 1
        }
        assert(head.turn == this, s"task queue of $this contains different turn's $head")
        head.compute()
        awaitAndSwitchPhase0(firstUnknownPredecessorIndex, FullMVTurn.INITIAL_BACKOFF, null)
      } else if (firstUnknownPredecessorIndex == selfNode.size) {
        assert(registeredForWaiting == null, s"$this is still registered on $registeredForWaiting as waiter despite having finished waiting for it")
        phaseLock.writeLock.lock()
        // make thread-safe sure that we haven't received any new predecessors that might
        // not be in the next phase yet. Only once that's sure we can also thread-safe sure
        // check that no predecessors pushed any tasks into our queue anymore. And only then
        // can we phase switch.
        if (firstUnknownPredecessorIndex == selfNode.size && taskQueue.isEmpty) {
          this.phase = newPhase
          phaseLock.writeLock.unlock()
          val it = waiters.entrySet().iterator()
          while (it.hasNext) {
            val waiter = it.next()
            if (FullMVEngine.DEBUG) println(s"[${Thread.currentThread().getName}] ${FullMVTurn.this} phase switch unparking ${waiter.getKey.getName}.")
            if (waiter.getValue <= newPhase) LockSupport.unpark(waiter.getKey)
          }
          if (FullMVEngine.DEBUG) println(s"[${Thread.currentThread().getName}] $this switched phase.")
        } else {
          phaseLock.writeLock.unlock()
          awaitAndSwitchPhase0(firstUnknownPredecessorIndex, FullMVTurn.INITIAL_BACKOFF, null)
        }
      } else {
        val currentUnknownPredecessor = selfNode.children(firstUnknownPredecessorIndex).txn
        if(currentUnknownPredecessor.phase < newPhase) {
          if (registeredForWaiting != null) {
            if (FullMVEngine.DEBUG) println(s"[${Thread.currentThread().getName}] $this parking for $currentUnknownPredecessor.")
            LockSupport.park(currentUnknownPredecessor)
            if (FullMVEngine.DEBUG) println(s"[${Thread.currentThread().getName}] $this unparked.")
            awaitAndSwitchPhase0(firstUnknownPredecessorIndex, 0L, currentUnknownPredecessor)
          } else if (backOff < FullMVTurn.MAX_BACKOFF) {
            val end = System.nanoTime() + backOff
            do {
              Thread.`yield`()
            } while (System.nanoTime() < end)
            awaitAndSwitchPhase0(firstUnknownPredecessorIndex, backOff + (backOff >> 2), null)
          } else {
            currentUnknownPredecessor.waiters.put(this.userlandThread, newPhase)
            awaitAndSwitchPhase0(firstUnknownPredecessorIndex, 0L, currentUnknownPredecessor)
          }
        } else {
          if (registeredForWaiting != null) currentUnknownPredecessor.waiters.remove(this.userlandThread)
          awaitAndSwitchPhase0(firstUnknownPredecessorIndex + 1, FullMVTurn.INITIAL_BACKOFF, null)
        }
      }
    }
    awaitAndSwitchPhase0(0, 0L, null)
  }

  private def beginPhase(phase: TurnPhase.Type): Unit = {
    assert(this.phase == TurnPhase.Initialized, s"$this already begun")
    assert(taskQueue.isEmpty, s"$this cannot begin $phase: queue non empty!")
    assert(selfNode.size == 0, s"$this cannot begin $phase: already has predecessors!")
    if (FullMVEngine.DEBUG) println(s"[${Thread.currentThread().getName}] begun.")
    this.phase = phase
  }

  def beginFraming(): Unit = beginPhase(TurnPhase.Framing)
  def beginExecuting(): Unit = beginPhase(TurnPhase.Executing)

  def completeFraming(): Unit = {
    assert(this.phase == TurnPhase.Framing, s"$this cannot complete framing: Not in framing phase")
//    restarts = 0
    awaitAndSwitchPhase(TurnPhase.Executing)
//    val contained1 = FullMVTurn.framingStats.get(restarts)
//    if(contained1 == null) {
//      val put = new AtomicLong()
//      val prev = FullMVTurn.framingStats.putIfAbsent(restarts, put)
//      if(prev == null) put else prev
//    } else { contained1 }.getAndIncrement()
  }
  def completeExecuting(): Unit = {
    assert(this.phase == TurnPhase.Executing, s"$this cannot complete executing: Not in executing phase")
    //    restarts = 0
    awaitAndSwitchPhase(TurnPhase.Completed)
    predecessorSpanningTreeNodes = Map.empty
    selfNode = null
//      val contained2 = FullMVTurn.executingStats.get(restarts)
//      if(contained2 == null) {
//        val put = new AtomicLong()
//        val prev = FullMVTurn.executingStats.putIfAbsent(restarts, put)
//        if(prev == null) put else prev
//      } else { contained2 }.getAndIncrement()
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

  def acquirePhaseLockIfAtMost(maxPhase: TurnPhase.Type): TurnPhase.Type = {
    val pOptimistic = phase
    if(pOptimistic > maxPhase) {
      pOptimistic
    } else {
      phaseLock.readLock().lock()
      val pSecure = phase
      if (pSecure > maxPhase) phaseLock.readLock().unlock()
      pSecure
    }
  }

  def addPredecessor(predecessorSpanningTree: TransactionSpanningTreeNode[FullMVTurn]): Unit = {
    assert(SerializationGraphTracking.lock.isHeldByCurrentThread, s"addPredecessor by thread that doesn't hold the SGT lock")
    assert(!isTransitivePredecessor(predecessorSpanningTree.txn), s"attempted to establish already existing predecessor relation ${predecessorSpanningTree.txn} -> $this")
    if(FullMVEngine.DEBUG) println(s"[${Thread.currentThread().getName}] $this new predecessor ${predecessorSpanningTree.txn}.")
    for(succ <- successorsIncludingSelf) succ.maybeNewReachableSubtree(this, predecessorSpanningTree)
  }

  def maybeNewReachableSubtree(attachBelow: FullMVTurn, spanningSubTreeRoot: TransactionSpanningTreeNode[FullMVTurn]): Unit = {
    if (!isTransitivePredecessor(spanningSubTreeRoot.txn)) {
      copySubTreeRootAndAssessChildren(attachBelow, spanningSubTreeRoot)
    }
  }

  private def copySubTreeRootAndAssessChildren(attachBelow: FullMVTurn, spanningSubTreeRoot: TransactionSpanningTreeNode[FullMVTurn]): Unit = {
    val newTransitivePredecessor = spanningSubTreeRoot.txn
    // last chance to check if predecessor completed concurrently
    if(newTransitivePredecessor.phase != TurnPhase.Completed) {
      newTransitivePredecessor.newSuccessor(this)
      val copiedSpanningTreeNode = new MutableTransactionSpanningTreeNode(newTransitivePredecessor)
      predecessorSpanningTreeNodes += newTransitivePredecessor -> copiedSpanningTreeNode
      predecessorSpanningTreeNodes(attachBelow).addChild(copiedSpanningTreeNode)

      val it = spanningSubTreeRoot.iterator()
      while(it.hasNext) {
        maybeNewReachableSubtree(newTransitivePredecessor, it.next())
      }
    }
  }

  def newSuccessor(successor: FullMVTurn): Unit = successorsIncludingSelf += successor

  def asyncReleasePhaseLock(): Unit = phaseLock.readLock().unlock()

  //========================================================ToString============================================================

  override def toString: String = s"FullMVTurn($hc by ${if(userlandThread == null) "none" else userlandThread.getName}, ${TurnPhase.toString(phase)}${if(taskQueue.size() != 0) s"(${taskQueue.size()})" else ""})"

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
    assert(Thread.currentThread() == userlandThread, s"$this ignition of $reactive on different thread ${Thread.currentThread().getName}")
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
    ignitionNotification.deliverNotification() match {
      case NotGlitchFreeReady =>
        if (FullMVEngine.DEBUG) println(s"[${Thread.currentThread().getName}] $this ignite $reactive did not spawn reevaluation.")
      // ignore
      case GlitchFreeReady =>
        if (FullMVEngine.DEBUG) println(s"[${Thread.currentThread().getName}] $this ignite $reactive spawned reevaluation.")
        Reevaluation(this, reactive).compute()
      case NextReevaluation(out, succTxn) if out.isEmpty =>
        if (FullMVEngine.DEBUG) println(s"[${Thread.currentThread().getName}] $this ignite $reactive spawned reevaluation for successor $succTxn.")
        succTxn.offer(Reevaluation(succTxn, reactive))
        LockSupport.unpark(succTxn.userlandThread)
      case otherOut: NotificationOutAndSuccessorOperation[FullMVTurn, Reactive[FullMVStruct]] if otherOut.out.isEmpty =>
        // ignore
      case other =>
        throw new AssertionError(s"$this ignite $reactive: unexpected result: $other")
    }
  }


  override private[rescala] def discover(node: ReSource[FullMVStruct], addOutgoing: Reactive[FullMVStruct]): Unit = {
    val r@(successorWrittenVersions, maybeFollowFrame) = node.state.discover(this, addOutgoing)
    assert((successorWrittenVersions ++ maybeFollowFrame).forall(retrofit => retrofit == this || retrofit.isTransitivePredecessor(this)), s"$this retrofitting contains predecessors: discover $node -> $addOutgoing retrofits $r from ${node.state}")
    if (FullMVEngine.DEBUG) println(s"[${Thread.currentThread().getName}] Reevaluation($this,$addOutgoing) discovering $node -> $addOutgoing re-queueing $successorWrittenVersions and re-framing $maybeFollowFrame")
    addOutgoing.state.retrofitSinkFrames(successorWrittenVersions, maybeFollowFrame, 1)
  }

  override private[rescala] def drop(node: ReSource[FullMVStruct], removeOutgoing: Reactive[FullMVStruct]): Unit = {
    val r@(successorWrittenVersions, maybeFollowFrame) = node.state.drop(this, removeOutgoing)
    assert((successorWrittenVersions ++ maybeFollowFrame).forall(retrofit => retrofit == this || retrofit.isTransitivePredecessor(this)), s"$this retrofitting contains predecessors: drop $node -> $removeOutgoing retrofits $r from ${node.state}")
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

object FullMVTurn {
  val INITIAL_BACKOFF = 10000L // 10µs
  val MAX_BACKOFF = 100000L // 100µs
//  val framingStats = new ConcurrentHashMap[Int, AtomicLong]()
//  val executingStats = new ConcurrentHashMap[Int, AtomicLong]()
}
