package main.abstraction

import react._
import macro.SignalMacro.{SignalM => Signal}

abstract class SignalWrapper {
	type InternalType;
	
	protected val internalValue: Var[Signal[InternalType]] 

	    
	protected def liftPure0[ResultT](f: InternalType => ResultT)(): Signal[ResultT] = 
	    Signal(f(internalValue()()))

	protected def liftMutating0(f: InternalType => InternalType)() {
	    val signal = internalValue()
	    internalValue() = Signal(f(signal()))
	}

	protected def liftPure1[Param1T, ResultT](f: (InternalType, Param1T) => ResultT)(p1: Signal[Param1T]): Signal[ResultT] =
	    Signal(f(internalValue()(), p1()))
	    
	protected def liftMutating1[Param1T](f: (InternalType, Param1T) => InternalType)(p1: Signal[Param1T]) {
	    val signal = internalValue()
	    internalValue() = Signal(f(signal(), p1()))
	}
	
	protected def liftPure2[Param1T, Param2T, ResultT](f: (InternalType, Param1T, Param2T) => ResultT)(p1: Signal[Param1T], p2: Signal[Param2T]): Signal[ResultT] =
	    Signal(f(internalValue()(), p1(), p2()))
	    
	protected def liftMutating2[Param1T, Param2T](f: (InternalType, Param1T, Param2T) => InternalType)(p1: Signal[Param1T], p2: Signal[Param2T]) {
	    val signal = internalValue()
	    internalValue() = Signal(f(signal(), p1(), p2()))
	}
	
	protected def liftPure3[Param1T, Param2T, Param3T, ResultT](f: (InternalType, Param1T, Param2T, Param3T) => ResultT)(p1: Signal[Param1T], p2: Signal[Param2T], p3: Signal[Param3T]): Signal[ResultT] =
	    Signal(f(internalValue()(), p1(), p2(), p3()))
	    
	protected def liftMutating3[Param1T, Param2T, Param3T](f: (InternalType, Param1T, Param2T, Param3T) => InternalType)(p1: Signal[Param1T], p2: Signal[Param2T], p3: Signal[Param3T]) {
	    val signal = internalValue()
	    internalValue() = Signal(f(signal(), p1(), p2(), p3()))
	}
	
	protected def liftPure4[Param1T, Param2T, Param3T, Param4T, ResultT](f: (InternalType, Param1T, Param2T, Param3T, Param4T) => ResultT)(p1: Signal[Param1T], p2: Signal[Param2T], p3: Signal[Param3T], p4: Signal[Param4T]): Signal[ResultT] =
	    Signal(f(internalValue()(), p1(), p2(), p3(), p4()))
	    
	protected def liftMutating4[Param1T, Param2T, Param3T, Param4T](f: (InternalType, Param1T, Param2T, Param3T, Param4T) => InternalType)(p1: Signal[Param1T], p2: Signal[Param2T], p3: Signal[Param3T], p4: Signal[Param4T]) {
	    val signal = internalValue()
	    internalValue() = Signal(f(signal(), p1(), p2(), p3(), p4()))
	}
}