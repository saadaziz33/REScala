package rescala.graph

import rescala.graph.RPValueWrappers.{PersistentValue, TransientPulse}
import rescala.propagation.Turn

import scala.language.{existentials, higherKinds, implicitConversions}

trait PersistentReevaluationStruct[V, R] {
  def reevDone(turn: Turn[_], value: Option[PersistentValue[V]], incomings: Set[Reactive[R]]): Unit
  def reevIn(turn: Turn[_]): (PersistentValue[V], Set[Reactive[R]])
}

trait TransientReevaluationStruct[P, R] {
  def reevDone(turn: Turn[_], value: TransientPulse[P], incomings: Set[Reactive[R]]): Unit
  def reevIn(turn: Turn[_]): Set[Reactive[R]]
}


trait SimplePropagationStruct[R] {
  def reevOut(turn: Turn[_]): Set[Reactive[R]]
}

trait AccessStruct[D, R] {
  def now(turn: Turn[_]): D
  def after(turn: Turn[_]): D
  def regRead(turn: Turn[_]): D
  def drop(turn: Turn[_], remove: Reactive[R]): Unit
  def discover(turn: Turn[_], add: Reactive[R]): D
}

trait PersistentAccessStruct[P, R] extends AccessStruct[PersistentValue[P], R] {
  def before(turn: Turn[_]): PersistentValue[P]
}
trait TransientAccessStruct[P, R] extends AccessStruct[TransientPulse[P], R]
