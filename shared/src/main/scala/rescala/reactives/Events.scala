package rescala.reactives

import rescala.engines.Ticket
import rescala.graph.Pulse.{Change, Exceptional, NoChange, Stable}
import rescala.graph._
import rescala.propagation.Turn
import rescala.reactives.RExceptions.EmptySignalControlThrowable

import scala.util.{Failure, Success}

object Events {


  private class StaticEvent[T, S <: Struct](_bud: S#SporeP[T, Reactive[S]], expr: Turn[S] => Pulse[T], override val toString: String)
    extends Base[T, S](_bud) with Event[T, S] with StaticReevaluation[T, S] {
    override def calculatePulse()(implicit turn: Turn[S]): Pulse[T] = Pulse.tryCatch(expr(turn))
  }

  private class DynamicEvent[T, S <: Struct](_bud: S#SporeP[T, Reactive[S]], expr: Turn[S] => Pulse[T]) extends Base[T, S](_bud) with Event[T, S] with DynamicReevaluation[T, S] {
    def calculatePulseDependencies(implicit turn: Turn[S]): (Pulse[T], Set[Reactive[S]]) = {
      val (newValueTry, dependencies) = turn.collectMarkedDependencies { RExceptions.reTry(expr(turn)) }
      newValueTry match {
        case Success(p) => (p, dependencies)
        case Failure(t : EmptySignalControlThrowable) => (Pulse.NoChange, dependencies)
        case Failure(t) => (Pulse.Exceptional(t), dependencies)
      }
    }
  }

  /** the basic method to create static events */
  def static[T, S <: Struct](name: String, dependencies: Reactive[S]*)(calculate: Turn[S] => Pulse[T])(implicit ticket: Ticket[S]): Event[T, S] = ticket { initTurn =>
    val dependencySet: Set[Reactive[S]] = dependencies.toSet
    initTurn.create(dependencySet) {
      new StaticEvent[T, S](initTurn.bud(initialIncoming = dependencySet, transient = true), calculate, name)
    }
  }

  /** create dynamic events */
  def dynamic[T, S <: Struct](dependencies: Reactive[S]*)(expr: Turn[S] => Option[T])(implicit ticket: Ticket[S]): Event[T, S] = {
    ticket { initialTurn =>
      initialTurn.create(dependencies.toSet, dynamic = true)(
        new DynamicEvent[T, S](initialTurn.bud(transient = true), expr.andThen(Pulse.fromOption)))
    }
  }


  /** A wrapped event inside a signal, that gets "flattened" to a plain event node */
  def wrapped[T, S <: Struct](wrapper: Signal[Event[T, S], S])(implicit ticket: Ticket[S]): Event[T, S] = ticket { creationTurn =>
    creationTurn.create(Set[Reactive[S]](wrapper), dynamic = true) {
      new Base[T, S](creationTurn.bud(transient = true)) with Event[T, S] with DynamicReevaluation[T, S] {
        override def calculatePulseDependencies(implicit turn: Turn[S]): (Pulse[T], Set[Reactive[S]]) = {
          wrapper.pulse match {
            case Change(inner) =>
              turn.dependencyInteraction(inner)
              (inner.pulse, Set(wrapper, inner))
            case Stable(inner) =>
              turn.dependencyInteraction(inner)
              (inner.pulse, Set(wrapper, inner))
            case NoChange => (NoChange, Set(wrapper))
            case ex @ Exceptional(_) => (ex, Set(wrapper))
          }
        }
      }
    }
  }
}
