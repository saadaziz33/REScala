package rescala.levelbased

import rescala.core.{Struct, Turn}
import rescala.twoversion._

import scala.language.higherKinds

/**
  * Wrapper for a struct type that combines GraphSpore, PulsingSpore and is leveled
  */
trait LevelStruct extends TwoVersionStruct {
  override type State[P, S <: Struct] <: LevelStructType[S] with GraphStructType[S] with ReadWriteValue[P, S]
}

/**
  * Wrapper for the instance of LevelSpore
  */
trait SimpleStruct extends LevelStruct {
  override type State[P, S <: Struct] = LevelStructTypeImpl[P, S]
}

/**
  * Graph struct that additionally can be assigned a level value that is used for topologically traversing the graph.
  *
  * @tparam S Type of the reactive values that are connected to this struct
  */
trait LevelStructType[S <: Struct] extends GraphStructType[S] {
  def level(turn: Turn[S]): Int
  def updateLevel(i: Int)(turn: Turn[S]): Int
}

/**
  * Implementation of a struct with graph and buffered pulse storage functionality that also support setting a level.
  *
  * @param current         Pulse used as initial value for the struct
  * @param transient       If a struct is marked as transient, changes to it can not be committed (and are released instead)
  * @tparam P Pulse stored value type
  * @tparam S Type of the reactive values that are connected to this struct
  */
class LevelStructTypeImpl[P, S <: Struct](current: P, transient: Boolean)
  extends PropagationStructImpl[P, S](current, transient) with LevelStructType[S] {

  var _level: Int = 0

  override def level(turn: Turn[S]): Int = _level

  override def updateLevel(i: Int)(turn: Turn[S]): Int = {
    val max = math.max(i, _level)
    _level = max
    max
  }
}

