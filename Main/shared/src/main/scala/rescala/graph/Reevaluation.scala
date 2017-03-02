package rescala.graph

import rescala.engine.Engine
import rescala.graph.Pulse.{Change, Exceptional, NoChange}
import rescala.graph.RPValueWrappers.{PersistentValue, TransientPulse}
import rescala.propagation.{DynamicReevaluationTicket, ReevaluationTicket, StaticReevaluationTicket, Turn}


trait Reevaluation[R] extends Reactive[R] {
  protected[rescala] type In
  protected[rescala] type Out <: Pulse[_]
  override protected[rescala] type Struct <: ReevaluationStruct[In, Out, R]
  protected[rescala] type Ticket <: ReevaluationTicket[R]
  protected[rescala] def computeResult(in: In, ticket: Ticket): Out
}

/**
  * Implementation of static re-evaluation of a reactive value.
  * Only calculates the stored value of the pulse and compares it with the old value.
  *
  * @tparam P Value type stored by the reactive value and its pulse
  * @tparam S Struct type that defines the spore type used to manage the reactive evaluation
  */
trait StaticReevaluation[R] extends Reevaluation[R] {
  override protected[rescala] type Ticket = StaticReevaluationTicket[R]
  protected[rescala] def reevaluate(turn: Turn[R]): ReevaluationResult = {
    val (in, incoming) = struct.reevIn(turn)
    val result = computeResult(in, turn.staticReevaluationTicket)
    struct.reevDone(turn, result, incoming)
    ReevaluationResult(result.isDefined, false)
  }
}


/**
  * Implementation of dynamic re-evaluation of a reactive value.
  * Calculates the pulse and new dependencies, compares them with the old value and dependencies and returns the result.
  *
  * @tparam P Value type stored by the reactive value and its pulse
  * @tparam S Struct type that defines the spore type used to manage the reactive evaluation
  */
trait DynamicReevaluation[R] extends Reevaluation[R] {
  override protected[rescala] type Ticket = StaticReevaluationTicket[R]
  protected[rescala] def reevaluate(turn: Turn[R]): ReevaluationResult = {
    val (in, incoming) = struct.reevIn(turn)
    val ticket = new DynamicReevaluationTicket[R](turn, incoming)
    val result = computeResult(in, ticket)
    struct.reevDone(turn, result, ticket.collectedDependencies)
    ReevaluationResult(result.isChange, ticket.incomingsChanged)
  }
}

trait TransientReevaluation[P, R] extends Reevaluation[R] {
  override protected[rescala] type In = Unit
  override protected[rescala] type Out = TransientPulse[P]

  protected[rescala] def computePulse(ticket: Ticket): TransientPulse[P]
  final override protected[rescala] def computeResult(in: Unit, ticket: Ticket): TransientPulse[P] = {
    computePulse()
  }
}

trait PersistentReevaluation[V, R] extends Reevaluation[R] {
  override protected[rescala] type In = PersistentValue[V]
  override protected[rescala] type Out = PersistentValue[V]

  protected[rescala] def computeValue(in: PersistentValue[V], ticket: Ticket): PersistentValue[V]
  final override protected[rescala] def computeResult(in: PersistentValue[V], ticket: Ticket): PersistentValue[V] = {
    val newValue = computeValue(in, ticket)
    newValue match {
      case NoChange => throw new AssertionError("NoChange is not a valid signal user computation result value.")
      case ex: Exceptional => ex
      case v: Change[V] if v == in => NoChange
      case v: Change[V] => v
    }
  }
}

trait ObserverReevaluation[R] extends Reevaluation[R] {
  override protected[rescala] type In = Unit
  override protected[rescala] type Out = Pulse[Nothing]

  protected[rescala] def execute(ticket: Ticket): Unit
  final override protected[rescala] def computeResult(in: Unit, ticket: Ticket): Unit = {
    execute(ticket)
    NoChange
  }
}

trait Disconnectable[R] extends Reevaluation[R] {
  @volatile private var disconnected = false

  final def disconnect()(implicit engine: Engine[R, Turn[R]]): Unit = {
    engine.plan(this) { turn =>
      disconnected = true
    }
  }

  abstract override protected[rescala] def reevaluate(turn: Turn[R]): ReevaluationResult = {
    if (disconnected) {
      val(_, incomings) = struct.reevIn(turn)
      for(drop <- incomings) drop.struct.drop(turn, this)
      struct.reevDone(turn, None, Set.empty)
      ReevaluationResult(changed = false, incomings.isEmpty)
    } else {
      super.reevaluate(turn)
    }
  }
}
