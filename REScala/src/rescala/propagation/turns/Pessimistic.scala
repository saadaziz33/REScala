package rescala.propagation.turns

import rescala.propagation.EvaluationResult.{Done, DependencyDiff}
import rescala.propagation.{TurnFactory, Turn, Reactive}

import scala.annotation.tailrec
import scala.collection.mutable
import scala.util.DynamicVariable

object Pessimistic extends TurnFactory {

  val currentTurn: DynamicVariable[Option[Turn]] = new DynamicVariable[Option[Turn]](None)

  override def maybeDynamicTurn[T](f: (Turn) => T): T = currentTurn.value match {
    case None => newTurn(f)
    case Some(turn) => f(turn)
  }

  def reachable(reactives: Set[Reactive])(implicit turn: Turn): Set[Reactive] =
    reactives ++ reactives.flatMap(r => reachable(r.dependants.get))

  def lock(reactives: Seq[Reactive])(implicit turn: Turn): Unit = reactives.sortBy(r => System.identityHashCode(r.lock)).foreach(_.lock.lock())
  def unlock(reactives: Seq[Reactive]): Unit = reactives.foreach(_.lock.unlock())

  override def newTurn[T](f: Turn => T): T = {
    val turn = new Pessimistic()
    val result = currentTurn.withValue(Some(turn)) {
      val res = f(turn)
      val sources = turn.evalQueue.map(_._2).toList
      lock(sources)(turn)
      val locked = reachable(sources.toSet)(turn).toSeq
      lock(locked)(turn)
      //TODO: need to check if the dependencies have changed in between
      //TODO: … it might actually be better to lock directly and always do deadlock detection
      try {
        turn.evaluateQueue()
        turn.commit()
      } finally {
        unlock(sources)
        unlock(locked)
        unlock(turn.dynamicLocks)
      }
      res
    }
    turn.runAfterCommitHandlers()
    result
  }

  val reactiveOrdering = new Ordering[(Int, Reactive)] {
    override def compare(x: (Int, Reactive), y: (Int, Reactive)): Int = y._1.compareTo(x._1)
  }

}

class Pessimistic extends Turn {
  private val evalQueue = new mutable.PriorityQueue[(Int, Reactive)]()(Synchronized.reactiveOrdering)
  private var toCommit = Set[Reactive]()
  private var afterCommitHandlers = List[() => Unit]()
  private var dynamicLocks = List[Reactive]()

  implicit def implicitThis: Turn = this

  def register(dependant: Reactive, dependencies: Set[Reactive]): Unit = {
    dependencies.foreach { dependency =>
      dependency.dependants.transform(_ + dependant)
      changed(dependency)
    }
    ensureLevel(dependant, dependencies)
  }

  def ensureLevel(dependant: Reactive, dependencies: Set[Reactive]): Unit =
    if (dependencies.nonEmpty) {
      if (setLevelIfHigher(dependant, dependencies.map(_.level.get).max + 1)) {
        changed(dependant)
      }
    }

  def setLevelIfHigher(reactive: Reactive, level: Int): Boolean = {
    reactive.level.transform { case x if x < level => level }
  }

  override def unregister(dependant: Reactive, dependencies: Set[Reactive]): Unit = dependencies.foreach { dependency =>
    dependencies.foreach(acquireDynamic)
    dependency.dependants.transform(_ - dependant)
    changed(dependency)
  }

  def handleDiff(dependant: Reactive, diff: DependencyDiff): Unit = {
    val DependencyDiff(newDependencies, oldDependencies) = diff
    newDependencies.foreach(acquireDynamic)
    unregister(dependant, oldDependencies.diff(newDependencies))
    register(dependant, newDependencies.diff(oldDependencies))
  }

  override def isReady(reactive: Reactive, dependencies: Set[Reactive]) =
    dependencies.forall(_.level.get < reactive.level.get)

  @tailrec
  private def floodLevel(reactives: Set[Reactive]): Unit =
    if (reactives.nonEmpty) {
      val reactive = reactives.head
      changed(reactive)
      val level = reactive.level.get + 1
      val dependants = reactive.dependants.get
      val changedDependants = dependants.filter(setLevelIfHigher(_, level))
      floodLevel(reactives.tail ++ changedDependants)
    }

  /** Adds a dependant to the eval queue */
  override def enqueue(dep: Reactive): Unit = {
    if (!evalQueue.exists { case (_, elem) => elem eq dep }) {
      evalQueue.+=((dep.level.get, dep))
    }
  }


  /** evaluates a single reactive */
  def evaluate(head: Reactive): Unit = {
    val result = head.reevaluate()(this)
    result match {
      case Done(hasChanged, dependencyDiff) =>
        if (hasChanged) head.dependants.get.foreach(enqueue)
        dependencyDiff.foreach(handleDiff(head, _))
        changed(head)
      case diff@DependencyDiff(_, _) =>
        handleDiff(head, diff)
        floodLevel(Set(head))
        enqueue(head)
    }
  }

  /** Evaluates all the elements in the queue */
  def evaluateQueue() = {
    while (evalQueue.nonEmpty) {
      val (level, head) = evalQueue.dequeue()
      // check the level if it changed queue again
      if (level != head.level.get) enqueue(head)
      else evaluate(head)
    }
  }

  def changed(reactive: Reactive): Unit = {
    if (!reactive.lock.relock.isHeldByCurrentThread)
      throw new IllegalStateException(s"tried to change reactive $reactive but current thread has no lock")
    if (reactive.lock.owner != Some(this))
      throw new IllegalStateException(s"tried to change reactive $reactive but lock is owned by ${reactive.lock.owner}")
    toCommit += reactive
  }

  def commit() = toCommit.foreach(_.commit(this))

  override def afterCommit(handler: => Unit) = afterCommitHandlers ::= handler _

  def runAfterCommitHandlers() = afterCommitHandlers.foreach(_())

  val bag = new DynamicVariable(Set[Reactive]())
  override def collectDependencies[T](f: => T): (T, Set[Reactive]) = bag.withValue(Set()) { (f, bag.value) }
  override def useDependency(dependency: Reactive): Unit = bag.value = bag.value + dependency

  override def create[T <: Reactive](dependencies: Set[Reactive])(f: => T): T = {
    dependencies.foreach(acquireDynamic)
    val reactive = f
    reactive.lock.lock()
    dynamicLocks ::= reactive
    register(reactive, dependencies)
    reactive
  }

  override def createDynamic[T <: Reactive](dependencies: Set[Reactive])(f: => T): T = {
    dependencies.foreach(acquireDynamic)
    val reactive = f
    reactive.lock.lock()
    dynamicLocks ::= reactive
    ensureLevel(reactive, dependencies)
    evaluate(reactive)
    reactive
  }

  def acquireDynamic(reactive: Reactive): Unit = {
    if(!reactive.lock.tryLock()) {
      reactive.lock.tradeStealRights()
      reactive.lock.steal()
      throw new IllegalStateException(s"$this could not lock $reactive already locked by ${reactive.lock.owner}")
    }
    dynamicLocks ::= reactive
  }

}
