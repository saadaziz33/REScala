package rescala.graph

import rescala.graph.RPValueWrappers.{PersistentValue, TransientPulse}
import rescala.propagation.Turn

import scala.language.{existentials, higherKinds, implicitConversions}

trait ReevaluationStruct[D, R] {
  def reevDone(turn: Turn[_], value: D): Unit
}

trait PersistentReevaluationStruct[P, R] extends ReevaluationStruct[PersistentValue[P], R] {
  def reevIn(turn: Turn[_]): (PersistentValue[P], Set[R])
}

trait TransientReevaluationStruct[P, R] extends ReevaluationStruct[TransientPulse[P], R] {
  def reevIn(turn: Turn[_]): Set[R]
}


trait PropagationStruct[R] {
  def reevOut(turn: Turn[_]): Set[R]
}

trait AccessStruct[D <: Unwrap[_, _], R] {
  def now(turn: Turn[_]): D
  def after(turn: Turn[_]): D
  def regRead(turn: Turn[_]): D
  def drop(turn: Turn[_], remove: R): Unit
  def discover(turn: Turn[_], add: R): D
}

trait PersistentAccessStruct[P, R] extends AccessStruct[PersistentValue[P], R] {
  def before(turn: Turn[_]): PersistentValue[P]
}
trait TransientAccessStruct[P, R] extends AccessStruct[TransientPulse[P], R]
