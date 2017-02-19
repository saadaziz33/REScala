package rescala.graph

import rescala.engine.Engine
import rescala.propagation.Turn

/**
  * Calculates and stores added or removed dependencies of a reactive value.
  *
  * @param novel Set of dependencies after re-evaluation
  * @param old   Set of dependencies before re-evaluation
  * @tparam S Struct type that defines the spore type used to manage the reactive evaluation
  */
case class Diff[R](novel: Set[Reactive[R]], old: Set[Reactive[R]]) extends DepDiff[R] {
  lazy val added = novel.diff(old)
  lazy val removed = old.diff(novel)
}

/**
  * Implementation of static re-evaluation of a reactive value.
  * Only calculates the stored value of the pulse and compares it with the old value.
  *
  * @tparam P Value type stored by the reactive value and its pulse
  * @tparam S Struct type that defines the spore type used to manage the reactive evaluation
  */
trait StaticReevaluation[+P, S <: Struct] extends Disconnectable[S] {
  this: Pulsing[P, S] =>

  /** side effect free calculation of the new pulse for the current turn */
  protected[rescala] def calculatePulse()(implicit turn: Turn[S]): Pulse[P]

  final override protected[rescala] def computeReevaluationResult()(implicit turn: Turn[S]): ReevaluationResult[S] = {
    val p = calculatePulse()
    set(p)
    ReevaluationResult.Static(hasChanged)
  }


}


/**
  * Implementation of dynamic re-evaluation of a reactive value.
  * Calculates the pulse and new dependencies, compares them with the old value and dependencies and returns the result.
  *
  * @tparam P Value type stored by the reactive value and its pulse
  * @tparam S Struct type that defines the spore type used to manage the reactive evaluation
  */
trait DynamicReevaluation[+P, S <: Struct] extends Disconnectable[S] {
  this: Pulsing[P, S] =>

  /** side effect free calculation of the new pulse and the new dependencies for the current turn */
  def calculatePulseDependencies(implicit turn: Turn[S]): (Pulse[P], Set[Reactive[S]])

  final override protected[rescala] def computeReevaluationResult()(implicit turn: Turn[S]): ReevaluationResult[S] = {
    val (newPulse, newDependencies) = calculatePulseDependencies

    val oldDependencies = state.incoming
    set(newPulse)
    ReevaluationResult.Dynamic(hasChanged, DepDiff(newDependencies, oldDependencies))

  }
}

trait Disconnectable[R] {
  this: Reactive[R] =>

  @volatile private var disconnected = false

  final def disconnect()(implicit engine: Engine[R, Turn[R]]): Unit = {
    engine.plan(this) { turn =>
      disconnected = true
    }
  }

  protected[rescala] def computeReevaluationResult()(implicit turn: Turn[R]): ReevaluationResult[R]

  final override protected[rescala] def reevaluate()(implicit turn: Turn[R]): ReevaluationResult[R] = {
    if (disconnected) {
      ReevaluationResult.Dynamic(changed = false, DepDiff(novel = Set.empty, old = state.incoming))
    } else {
      computeReevaluationResult()
    }
  }

}
