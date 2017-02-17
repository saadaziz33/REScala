package rescala.graph

import rescala.reactives.RExceptions.EmptySignalControlThrowable
import tests.rescala.EmptySignalTestSuite

import scala.util.{Failure, Success, Try}

private sealed trait RPValueWrapper[+W] {
  self: Unwrap[W, _] =>
  /**
    * If the pulse indicates a change: Applies a function to the updated value of the pulse and returns a new pulse
    * indicating a change to this updated value.
    * If the pulse doesn't indicate a change: Returns an empty pulse indicating no change.
    *
    * @param f Function to be applied on the updated pulse value
    * @tparam Q Result type of the applied function
    * @return Pulse indicating the update performed by the applied function or an empty pulse if there is no updated value
    */
  def map[Q](f: W => Q): RPValueWrapper[Q] = this match {
    case ValueWrapper(value) => Change(f(value))
    case NoValue => NoChange
    case ex@ExceptionWrapper(_) => ex
  }

  /**
    * If the pulse indicates a change: Applies a function to the updated value. The function has to return a new pulse
    * that is returned by this function.
    * If the pulse doesn't indicate a change: Returns an empty pulse indicating no change.
    *
    * @param f Function to be applied on the updated pulse value
    * @tparam Q Value type of the pulse returned by the applied function
    * @return Pulse returned by the applied function or an empty pulse if there is no updated value
    */
  def flatMap[Q](f: W => RPValueWrapper[Q]): RPValueWrapper[Q] = this match {
    case ValueWrapper(value) => f(value)
    case NoValue => NoChange
    case ex@ExceptionWrapper(_) => ex
  }

  /**
    * If the pulse indicates a change: Applies a filter function to the updated value of the pulse.
    * Based on the filter function, the updated value is retained or an empty pulse is returned.
    * If the pulse doesn't indicate a change: Returns an empty pulse indicating no change.
    *
    * @param p Filter function to be applied to the updated pulse value
    * @return A pulse with the updated pulse value if the filter function returns true, an empty pulse otherwise
    */
  def filter(p: W => Boolean): RPValueWrapper[W] = this match {
    case c@ValueWrapper(value) if p(value) => c
    case ValueWrapper(_) => NoChange
    case NoValue => NoChange
    case ex@ExceptionWrapper(_) => ex
  }

  /** converts the pulse to an option of try */
  def toOptionTry: Option[Try[W]] = this match {
    case ValueWrapper(up) => Some(Success(up))
    case NoValue => None
    case ExceptionWrapper(t) => Some(Failure(t))
  }
}

case object NoValue extends RPValueWrapper[Nothing]
case class ValueWrapper[P](value: P) extends RPValueWrapper[P]
case class ExceptionWrapper(throwable: Throwable) extends RPValueWrapper[Nothing]

trait Unwrap[W, U] {
  self: ValueWrapper[W] =>
  def get: U
}

trait PersistentUnwrap[V] extends Unwrap[V, V] {
  override def get: V = this match {
    case ValueWrapper(up) => up
    case NoValue => throw EmptySignalControlThrowable
    case ExceptionWrapper(t) => throw t
  }
}

trait TransientUnwrap[P] extends Unwrap[P, Option[P]] {
  override def get: Option[P] = this match {
    case ValueWrapper(up) => Some(up)
    case NoValue => None
    case ExceptionWrapper(t) => throw t
  }
}

object RPValueWrappers {
  type PersistentValue[V] = RPValueWrapper[V] with PersistentUnwrap[V]
  type TransientPulse[P] = RPValueWrapper[P] with TransientUnwrap[P]
}
