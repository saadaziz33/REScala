package rescala.turns

import rescala.{Events, Signals, Signal}
import rescala.graph.{Reactive, Spores}
import rescala.macros.ReactiveMacros

import scala.annotation.implicitNotFound
import scala.language.experimental.macros


@implicitNotFound(msg = "could not find a propagation engine, select one from Engines")
trait Engine[S <: Spores, +TTurn <: Turn[S]] {

  type Signal[+A] = rescala.Signal[A, S]
  type Event[+A] = rescala.Event[A, S]
  type Var[A] = rescala.Var[A, S]
  type Evt[A] = rescala.Evt[A, S]
  type Spores = S
  type Turn = rescala.turns.Turn[S]
  type Ticket = rescala.turns.Ticket[S]
  type Reactive = rescala.graph.Reactive[S]
  def Evt[A](): Evt[A] = rescala.Evt[A, S]()(this)
  def Var[A](v: A): Var[A] = rescala.Var[A, S](v)(this)
  def dynamic[T](dependencies: Reactive*)(expr: Turn => T)(implicit ticket: Ticket): Signal[T] = Signals.dynamic(dependencies: _*)(expr)
  def dynamicE[T](dependencies: Reactive*)(expr: Turn => Option[T])(implicit ticket: Ticket): Event[T] = Events.dynamic(dependencies: _*)(expr)

  def Signal[A](expression: A): Signal[A] = macro ReactiveMacros.SignalMacro[A, S]
  def Event[A](expression: Option[A]): Event[A] = macro ReactiveMacros.EventMacro[A, S]


  /** used for the creation of state inside reactives */
  private[rescala] def bufferFactory: S

  /** creates runs and commits a new turn */
  def plan[R](initialWrites: Reactive*)(admissionPhase: TTurn => R): R

  /** uses the current turn if any or creates a new turn if none */
  def subplan[T](initialWrites: Reactive*)(admissionPhase: TTurn => T): T
}