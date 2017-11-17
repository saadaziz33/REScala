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
  def unlock(): UnlockedSameSCC.type = { lock.unlock(); UnlockedSameSCC }
}

object SerializationGraphTracking {
  val lock: ReentrantLock = new ReentrantLock()

  def acquireLock(defender: FullMVTurn, contender: FullMVTurn, sccState: SCCState): LockedSameSCC = {
    sccState match {
      case x@LockedSameSCC(_) => x
      case somethingUnlocked =>
        lock.lock()
        LockedSameSCC(lock)
    }
  }

  def tryLock(defender: FullMVTurn, contender: FullMVTurn, sccState: SCCState): SCCState = {
    sccState match {
      case x@LockedSameSCC(_) => x
      case somethingUnlocked =>
        if(lock.tryLock()) {
          LockedSameSCC(lock)
        } else {
          Thread.`yield`()
          somethingUnlocked
        }
    }
  }
}
