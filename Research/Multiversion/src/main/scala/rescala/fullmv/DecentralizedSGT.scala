package rescala.fullmv

import java.util.concurrent.locks.ReentrantLock

object DecentralizedSGT {
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
