package rescala.graph

import rescala.engine.TurnSource
import rescala.graph.Pulse._
import rescala.graph.RPValueWrappers.{PersistentValue, TransientPulse}
import rescala.propagation.{ReevaluationTicket, Turn}

import scala.annotation.compileTimeOnly

trait Node {
  type Struct
  protected[rescala] def struct: Struct
}

case class ReevaluationResult(valueChanged: Boolean, incomingChanged: Boolean)

/**
  * A reactive value is something that can be reevaluated
  *
  * @tparam R Struct type that defines the struct type used to manage the reactive evaluation
  */
trait Reactive[R] extends Node {
  final override val hashCode: Int = Globals.nextID().hashCode()

  override protected[rescala] type Struct// <: PropagationStruct[R]

  /**
    * Reevaluates this Reactive when it is internally scheduled for reevaluation.
    * Implementations should update their specialized [[struct]] such that
    * [[PropagationStruct.reevOut(turn)]] can be executed next.
    *
    * @param turn Turn that handles the reevaluation
    * @return Result of the reevaluation
    */
  protected[rescala] def reevaluate(turn: Turn[R]): ReevaluationResult

  /** for debugging */
  private val name = Globals.declarationLocationName()
  override def toString: String = name
}

trait AccessibleNode[T, D, R] extends Node {
  override protected[rescala] type Struct <: AccessStruct[D, R]

  protected[rescala] def access(storedData: D): T

  // only used inside macro and will be replaced there
  @compileTimeOnly("Signal.apply can only be used inside of Signal expressions")
  final def apply(): T = throw new IllegalAccessException(s"$this.apply called outside of macro")

  def after(ticket: ReevaluationTicket[R]): T = access(struct.after(ticket.turn))
}

trait PersistentAccessibleNode[V, R] extends AccessibleNode[V, PersistentValue[V], R] {
  override protected[rescala] type Struct <: PersistentAccessStruct[V, R]
  override protected[rescala] def access(persistentValue: PersistentValue[V]): V = persistentValue match {
    case Change(value) => value
    case Exceptional(t) => throw t
    case NoChange => throw new IllegalStateException("NoChange used as Signal value")
  }

  def now(turn: Turn[R]): V = access(struct.now(turn))
  def before(ticket: ReevaluationTicket[R]): V = access(struct.before(ticket.turn))
}
trait TransientAccessibleNode[P, R] extends AccessibleNode[Option[P], TransientPulse[P], R]  {
  override protected[rescala] type Struct <: TransientAccessStruct[P, R]
  override protected[rescala] def access(transientPulse: TransientPulse[P]): Option[P] = {
    case Change(update) => Some(update)
    case NoChange => None
    case Exceptional(t) => throw t
  }
}

