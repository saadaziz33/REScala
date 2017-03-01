package rescala.graph

import rescala.graph.RPValueWrappers.{PersistentValue, TransientPulse}
import rescala.propagation.Turn

import scala.language.{existentials, higherKinds, implicitConversions}

trait ReevaluationStruct[I, O, R] {
  def reevDone(turn: Turn[_], result: Option[O], incoming: Set[AccessibleNode[_, _, R]]): Unit
  def reevIn(turn: Turn[_]): (I, Set[AccessibleNode[_, _, R]])
}

trait PersistentReevaluationStruct[V, R] extends ReevaluationStruct[PersistentValue[V], PersistentValue[V], R]

trait TransientReevaluationStruct[P, R] extends ReevaluationStruct[Unit, TransientPulse[P], R]


trait SimplePropagationStruct[R] {
  def reevOut(turn: Turn[_]): Set[Reactive[R]]
}

trait AccessStruct[D, R] {
  def after(turn: Turn[_]): D
  def regRead(turn: Turn[_]): D
  def drop(turn: Turn[_], remove: Reactive[R]): Unit
  def discover(turn: Turn[_], add: Reactive[R]): D
}

trait PersistentAccessStruct[V, R] extends AccessStruct[PersistentValue[V], R] {
  def now(turn: Turn[_]): PersistentValue[V]
  def before(turn: Turn[_]): PersistentValue[V]
}
trait TransientAccessStruct[P, R] extends AccessStruct[TransientPulse[P], R]
