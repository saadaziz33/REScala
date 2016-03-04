package rescala.parrp

import java.util

import rescala.graph.ReevaluationResult.{Dynamic, Static}
import rescala.graph._
import rescala.locking._
import rescala.propagation.{CommonPropagationImpl, Turn}

object LSStruct extends PulsingGraphStruct {
  override type SporeP[P, R] = LSSporeP[P, R]
}


class LSSporeP[P, R](current: Pulse[P], transient: Boolean, val lock: TurnLock[LSInterTurn], initialIncoming: Set[R])
  extends PropagationSporeImpl[P, R](current, transient, initialIncoming) {

  override def set(value: Pulse[P])(implicit turn: Turn[_]): Unit = {
    assert(turn match {
      case pessimistic: LockSweep =>
        val wlo: Option[Key[LSInterTurn]] = Option(lock).map(_.getOwner)
        assert(wlo.fold(true)(_ eq pessimistic.key),
          s"buffer owned by $owner, controlled by $lock with owner ${wlo.get}" +
            s" was written by $turn who locks with ${pessimistic.key}, by now the owner is ${lock.getOwner}")
        true
      case _ =>
        throw new IllegalStateException(s"locksweep buffer used with wrong turn")
    })
    super.set(value)
  }

  var writeSet: LockSweep = null
  var color: LockSweep = null
  var counter: Int = 0
  override def release(implicit turn: Turn[_]): Unit = {
    super.release
  }
  override def toString(): String = s"Bud($counter)"
}

trait LSInterTurn {
  def append(reactives: Reactive[LSStruct.type]): Unit
}

class LockSweep(backoff: Backoff) extends CommonPropagationImpl[LSStruct.type] with LSInterTurn {

  private type TState = LSStruct.type

  override def bud[P, R](initialValue: Pulse[P], transient: Boolean, initialIncoming: Set[R]): LSStruct.SporeP[P, R] = {
    val lock = new TurnLock[LSInterTurn]
    new LSSporeP[P, R](initialValue, transient, lock, initialIncoming)
  }


  val queue = new util.ArrayDeque[Reactive[TState]]()

  final val key: Key[LSInterTurn] = new Key(this)

  var currentIndex = 0

  var discovered: List[(Key[LSInterTurn], Reactive[TState], Reactive[TState])] = Nil

  /** lock all reactives reachable from the initial sources
    * retry when acquire returns false */
  override def preparationPhase(initialWrites: List[Reactive[TState]]): Unit = {
    val stack = new java.util.ArrayDeque[Reactive[TState]](10)
    initialWrites.foreach(stack.push)

    val sorted = new util.ArrayList[Reactive[TState]]

    while (!stack.isEmpty) {
      val reactive = stack.pop()

      if (reactive.bud.lock.tryLock(key) eq key) {
        if (reactive.bud.writeSet == this) {
          reactive.bud.counter += 1
        }
        else {
          sorted.add(reactive)
          reactive.bud.counter = 1
          reactive.bud.writeSet = this
          reactive.bud.outgoing(this).foreach { r =>
            stack.push(r)
          }
        }
      }
      else {
        val it = sorted.iterator()
        while (it.hasNext) {
          val curr = it.next()
          curr.bud.writeSet = null
        }
        val sit = stack.iterator()
        while (sit.hasNext) {
          val curr = sit.next()
          if (curr.bud.writeSet == this) {
            curr.bud.writeSet = null
          }
        }
        sorted.clear()
        key.reset()
        stack.clear()
        initialWrites.foreach(stack.push)
        backoff.backoff()
      }
    }
    initialWrites.foreach(_.bud.counter = 0)
    initialWrites.foreach(enqueue)
  }

  override def propagationPhase(): Unit = {
    while (!queue.isEmpty) {
      evaluate(queue.pop())
    }
  }

  def done(head: Reactive[TState]): Unit = {
    head.bud.color = this
    head.bud.outgoing(this).foreach { r =>
      r.bud.counter -= 1
      if (r.bud.counter <= 0) enqueue(r)
    }
  }

  def enqueue(head: Reactive[TState]): Unit = {
    assert(head.bud.counter == 0, s"should only evaluate counted to 0 but is ${head.bud.counter}: $head")
    assert(head.bud.writeSet == this, s"should only evaluate reactives in the write set: $head")
    assert(head.bud.color != this, s"should not evaluate things twice: $head")
    queue.push(head)
  }

  def evaluate(head: Reactive[TState]): Unit = {
    assert(head.bud.counter == 0, s"should only evaluate counted to 0 but is ${head.bud.counter}: $head")
    assert(head.bud.writeSet == this, s"should only evaluate reactives in the write set: $head")
    assert(head.bud.color != this, s"should not evaluate things twice: $head")
    head.bud.writeSet == this && head.bud.color != this

    head.reevaluate()(this) match {
      case Static(hasChanged) => done(head)

      case Dynamic(hasChanged, diff) =>
        diff.removed foreach drop(head)
        diff.added foreach discover(head)
        head.bud.counter = recount(diff.novel)

        if (head.bud.counter == 0) done(head)

    }
  }

  def recount(reactives: Set[Reactive[TState]]): Int = {
    reactives.count(r => r.bud.color != this  && r.bud.writeSet == this)
  }

  /**
    * creating a signal causes some unpredictable reactives to be used inside the turn.
    * these will have their locks be acquired dynamically see below for how that works.
    * the newly created reactive on the other hand can not be locked by anything, so we just grab the lock
    * (we do need to grab it, so it can be transferred to some other waiting transaction).
    * it is important, that the locks for the dependencies are acquired BEFORE the constructor for the new reactive.
    * is executed, because the constructor typically accesses the dependencies to create its initial value.
    */
  override def create[T <: Reactive[TState]](dependencies: Set[Reactive[TState]], dynamic: Boolean)(f: => T): T = {
    dependencies.map(acquireShared)
    val reactive = f
    val owner = reactive.bud.lock.tryLock(key)
    reactive.bud.writeSet = this
    assert(owner eq key, s"$this failed to acquire lock on newly created reactive $reactive")

    if (dynamic) {
      enqueue(reactive)
    }
    else {
      dependencies.foreach(discover(reactive))
      reactive.bud.counter = recount(dependencies)
      if (reactive.bud.counter == 0) evaluate(reactive)
    }
    reactive
  }


  /** registering a dependency on a node we do not personally own does require some additional care.
    * we let the other turn update the dependency and admit the dependent into the propagation queue
    * so that it gets updated when that turn continues
    * the responsibility for correctly passing the locks is moved to the commit phase */
  def discover(sink: Reactive[TState])(source: Reactive[TState]): Unit = {

    val owner = acquireShared(source)
    if (owner ne key) {
      if (source.bud.writeSet != owner.turn) {
        source.bud.discover(sink)(this)
      }
      else if (!source.bud.outgoing(this).contains(sink)) {
        source.bud.discover(sink)(this)
        discovered ::= ((owner, sink, source))
        key.lockKeychain {_.addFallthrough(owner)}
      }
    }
    else {
      source.bud.discover(sink)(this)
    }
  }

  /** this is for cases where we register and then unregister the same dependency in a single turn */
  def drop(sink: Reactive[TState])(source: Reactive[TState]): Unit = {


    val owner = acquireShared(source)
    if (owner ne key) {
      source.bud.drop(sink)(this)
      if (source.bud.writeSet != owner.turn) {
        key.lockKeychain(_.removeFallthrough(owner))
        if (!sink.bud.incoming(this).exists(_.bud.lock.isOwner(owner))) {
          discovered = discovered.filter(_ != ((owner, sink, source)))
        }
      }
    }
    else source.bud.drop(sink)(this)
  }


  /** this is called after the turn has finished propagating, but before handlers are executed */
  override def releasePhase(): Unit = {
    discovered.reverse.foreach { case (other, sink, source) =>
      other.turn.append(sink)
    }
    key.releaseAll()
  }


  override def append(reactives: Reactive[TState]): Unit = {
    val stack = new java.util.ArrayDeque[Reactive[TState]](10)

    stack.push(reactives)

    while (!stack.isEmpty) {
      val reactive = stack.pop()

        if (reactive.bud.writeSet == this) {
          reactive.bud.counter += 1
        }
        else {
          reactive.bud.counter = 1
          reactive.bud.writeSet = this
          reactive.bud.outgoing(this).foreach { r =>
            stack.push(r)
          }
        }
    }

    reactives.bud.counter = recount(reactives.bud.outgoing(this))
    if (reactives.bud.counter == 0) enqueue(reactives)

  }


  /** allow turn to handle dynamic access to reactives */
  override def dependencyInteraction(dependency: Reactive[TState]): Unit = acquireShared(dependency)

  def acquireShared(reactive: Reactive[TState]): Key[LSInterTurn] = Keychains.acquireShared(reactive.bud.lock, key)
}
