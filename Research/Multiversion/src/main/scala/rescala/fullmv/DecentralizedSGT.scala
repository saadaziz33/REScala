package rescala.fullmv

import java.util.concurrent.locks.{Lock, ReentrantLock}

object DecentralizedSGT {
  val lock: Lock = new ReentrantLock()

  def acquireLock(defender: FullMVTurn, contender: FullMVTurn, sccState: SCCState): LockedSameSCC = {
    sccState match {
      case x@LockedSameSCC(_) => x
      case UnlockedSameSCC =>
        lock.lock()
        LockedSameSCC(lock)
      case UnlockedUnknown =>
        lock.lock()
        LockedSameSCC(lock)
    }
  }
}
