package rescala.fullmv

import rescala.graph.{Reactive, Struct}

trait FullMVStruct extends Struct {
  override type State[P, S <: Struct] = NodeVersionHistory[P, FullMVTurn, Reactive[FullMVStruct]]
}