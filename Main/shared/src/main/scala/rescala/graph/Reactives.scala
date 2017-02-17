package rescala.graph

import rescala.engine.TurnSource
import rescala.graph.RPValueWrappers.{PersistentValue, TransientPulse}
import rescala.propagation.{ReevaluationTicket, Turn}
import rescala.reactives.RExceptions.EmptySignalControlThrowable

import scala.annotation.compileTimeOnly

trait Node {
  type Struct
  protected[rescala] def struct: Struct
}

/**
  * A reactive value is something that can be reevaluated
  *
  * @tparam R Struct type that defines the struct type used to manage the reactive evaluation
  */
trait Reactive[R] extends Node {
  final override val hashCode: Int = Globals.nextID().hashCode()

  override protected[rescala] type Struct <: PropagationStruct[R]

  /**
    * Reevaluates this Reactive when it is internally scheduled for reevaluation.
    * Implementations should update their specialized [[struct]] such that
    * [[PropagationStruct.reevOut(turn)]] can be executed next.
    *
    * @param turn Turn that handles the reevaluation
    * @return Result of the reevaluation
    */
  protected[rescala] def reevaluate()(implicit turn: Turn[R]): ReevaluationResult[R]

  /** for debugging */
  private val name = Globals.declarationLocationName()
  override def toString: String = name
}

trait AccessibleNode[T, D <: Unwrap[_, T], R] extends Node {
  override protected[rescala] type Struct <: AccessStruct[D, R]

  // only used inside macro and will be replaced there
  @compileTimeOnly("Signal.apply can only be used inside of Signal expressions")
  final def apply(): T = throw new IllegalAccessException(s"$this.apply called outside of macro")

  def now(turn: Turn[R]): T = struct.now(turn).get
  def after(ticket: ReevaluationTicket[R]): T = struct.after(turn).get
}

trait PersistentAccessibleNode[V, R] extends Node {
  override protected[rescala] type Struct <: PersistentAccessStruct[V, R]
  def before(ticket: ReevaluationTicket[R]): V = struct.before(turn).get
}
trait TransientAccessibleNode[P, R] extends Node {
  override protected[rescala] type Struct <: TransientAccessStruct[P, R]
}

