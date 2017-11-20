package rescala.fullmv

import java.util.concurrent.locks.{Lock, ReentrantLock}

sealed trait SCCState {
  def unlockedIfLocked(): SCCConnectivity
  def known: SCCState
}
sealed trait SCCConnectivity extends SCCState {
  override def known: UnlockedSameSCC.type = UnlockedSameSCC
}
case object UnlockedUnknown extends SCCConnectivity {
  override def unlockedIfLocked(): this.type = this
}
case object UnlockedSameSCC extends SCCConnectivity {
  override def unlockedIfLocked(): this.type = this
}
case class LockedSameSCC(lock: Lock) extends SCCState {
  override def unlockedIfLocked(): UnlockedSameSCC.type = unlock()
  override def known: this.type = this
  def unlock(): UnlockedSameSCC.type = {
    lock.unlock()
//    SerializationGraphTracking.released()
    UnlockedSameSCC
  }
}

object SerializationGraphTracking /* extends LockContentionTimer */ {
  val lock: ReentrantLock = new ReentrantLock()
//  var out: PrintStream = null

  def acquireLock(defender: FullMVTurn, contender: FullMVTurn, sccState: SCCState): LockedSameSCC = {
    sccState match {
      case x@LockedSameSCC(_) =>
//        entered()
        x
      case somethingUnlocked =>
        lock.lock()
//        entered()
        LockedSameSCC(lock)
    }
  }

  def tryLock(defender: FullMVTurn, contender: FullMVTurn, sccState: SCCState): SCCState = {
    sccState match {
      case x@LockedSameSCC(_) =>
//        entered()
        x
      case somethingUnlocked =>
        if(lock.tryLock()) {
//          entered()
          LockedSameSCC(lock)
        } else {
          Thread.`yield`()
          somethingUnlocked
        }
    }
  }
}

//trait LockContentionTimer {
//  def out: PrintStream
//  val latestTimePerThread = new ThreadLocal[Long]
//
//  def startContending(): Unit = if(out != null) {
//    val now = System.nanoTime()
//    latestTimePerThread.set(now)
//  }
//  def abortContending(): Unit = if(out != null) {
//    val started = latestTimePerThread.get()
//    val now = System.nanoTime()
//    out.println("s\t"+(now - started))
//  }
//  def entered(): Unit = if(out != null) {
//    val started = latestTimePerThread.get()
//    val now = System.nanoTime()
//    out.println("e\t"+(now - started))
//    latestTimePerThread.set(now)
//  }
//  def released(): Unit = if(out != null) {
//    val started = latestTimePerThread.get()
//    val now = System.nanoTime()
//    out.println("r\t"+(now - started))
//  }
//}
