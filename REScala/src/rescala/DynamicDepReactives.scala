package rescala

import scala.collection.mutable.ListBuffer
import rescala.events.Event
import rescala.events.ChangedEventNode
import rescala.events.EventNode

import scala.concurrent.stm.{TxnLocal, Ref, atomic}

//trait FixedDepHolder extends Reactive {
//  val fixedDependents = new ListBuffer[Dependent]
//  def addFixedDependent(dep: Dependent) = fixedDependents += dep
//  def removeFixedDependent(dep: Dependent) = fixedDependents -= dep
// def notifyDependents(change: Any): Unit = dependents.map(_.dependsOnchanged(change,this))
//}

/* A node that has nodes that depend on it */
class VarSynt[T](value: T) extends Var[T] {

  private[this] val _value = Ref(value)

  def get = _value.single.get

  def set(newValue: T): Unit = atomic { tx =>
    val oldValue = _value.getAndTransform(_ => newValue)(tx)
    if (oldValue != newValue) {
      TS.nextRound() // Testing
      logTestingTimestamp()

      notifyDependents(newValue)
      ReactiveEngine.startEvaluation()

    } else {
      ReactiveEngine.log.nodePropagationStopped(this)
      logTestingTimestamp() // testing
    }
  }

  def reEvaluate(): T = value
}

object VarSynt {
  def apply[T](initialValue: T) = new VarSynt(initialValue)
}

trait DependentSignalImplementation[+T] extends DependentSignal[T] {

  def initialValue(): T
  def calculateNewValue(): T

  private[this] val currentValue = Ref(initialValue())

  def get = currentValue.single.get

  def triggerReevaluation(): Unit = {
    ReactiveEngine.log.nodeEvaluationStarted(this)

    logTestingTimestamp() // Testing

    val oldLevel = level

     // Evaluation
    val newValue = calculateNewValue()

    /* if the level increases by one, the dependencies might or might not have been evaluated this turn.
     * if they have, we could just fire the observers, but if they have not we are not allowed to do so
     *
     * if the level increases by more than one, we depend on something that still has to be in the queue
     */
    if (level == oldLevel + 1) {
      ReactiveEngine.addToEvalQueue(this)
    }
    else {
      if (level <= oldLevel) {
        val oldValue = currentValue.single.getAndTransform(_ => newValue)
        /* Notify dependents only of the value changed */
        if (oldValue != newValue) { notifyDependents(newValue) }
        else { ReactiveEngine.log.nodePropagationStopped(this) }
      } : Unit
    }
    ReactiveEngine.log.nodeEvaluationEnded(this)
  }
  override def dependsOnchanged(change: Any, dep: DepHolder) = ReactiveEngine.addToEvalQueue(this)

}

/** A dependant reactive value with dynamic dependencies (depending signals can change during evaluation) */
class SignalSynt[+T](reactivesDependsOnUpperBound: List[DepHolder])(expr: SignalSynt[T] => T)
  extends { private val detectedDependencies = TxnLocal(Set[DepHolder]()) } with DependentSignalImplementation[T] {

  override def onDynamicDependencyUse[A](dependency: Signal[A]): Unit = atomic { tx =>
    super.onDynamicDependencyUse(dependency)
    detectedDependencies.transform(_ + dependency)(tx)
  }

  override def initialValue(): T = calculateNewValue()

  override def calculateNewValue(): T = atomic { tx =>
    val newValue = expr(this)
    setDependOn(detectedDependencies.getAndTransform(_ => Set())(tx))
    newValue
  }

  private val upperBoundLevel = if(reactivesDependsOnUpperBound.isEmpty) 0 else reactivesDependsOnUpperBound.map{_.level}.max + 1

  override def level: Int = super.level.max(upperBoundLevel)
}

/**
 * A syntactic signal
 */
object SignalSynt {
  def apply[T](reactivesDependsOn: List[DepHolder])(expr: SignalSynt[T] => T) =
    new SignalSynt(reactivesDependsOn)(expr)

  def apply[T](expr: SignalSynt[T] => T): SignalSynt[T] = apply(List())(expr)
  def apply[T](dependencyHolders: DepHolder*)(expr: SignalSynt[T] => T): SignalSynt[T] = apply(dependencyHolders.toList)(expr)

}





/** A wrapped event inside a signal, that gets "flattened" to a plain event node */
class WrappedEvent[T](wrapper: Signal[Event[T]]) extends EventNode[T] with Dependent {
  
  val currentValue: TxnLocal[T] = TxnLocal[T]()
  
  updateDependencies()

  private def updateDependencies() = setDependOn(Set(wrapper, wrapper.get))

  def triggerReevaluation() = atomic { tx =>
    logTestingTimestamp()
    notifyDependents(currentValue.get(tx))
  }
  
  override def dependsOnchanged(change: Any, dep: DepHolder) = {
    if(dep eq wrapper) {
	    updateDependencies()
    }
    else if(dep eq wrapper.get) atomic { tx =>
      currentValue.set(change.asInstanceOf[T])(tx)
    	ReactiveEngine.addToEvalQueue(this)
    }
    else throw new IllegalStateException("Illegal DepHolder " + dep)

  }
  
}
