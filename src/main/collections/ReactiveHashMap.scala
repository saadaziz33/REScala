package main.collections

import react._
import scala.collection.immutable._
import main.abstraction._

class ReactiveHashMap[A,B](map: Signal[Map[A,B]]) extends ReactiveMap[A,B, ReactiveHashMap] {
	override protected val internalValue = Var(map)
	
	def this(map: HashMap[A,B]) = this(Var(map).toSignal)
	def this(pairs: (A,B)*) = this(HashMap(pairs:_*))
}

object ReactiveHashMap {
    implicit def wrapping[C,D] = new SignalWrappable[Map[C,D], ReactiveHashMap[C,D]] {
	    def wrap(unwrapped: Signal[Map[C,D]]): ReactiveHashMap[C,D] = new ReactiveHashMap(unwrapped)
	}
}
