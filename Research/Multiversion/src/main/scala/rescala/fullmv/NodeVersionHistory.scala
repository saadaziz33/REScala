package rescala.fullmv

import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ForkJoinPool.ManagedBlocker
import java.util.concurrent.locks.Lock

import rescala.core.ValuePersistency
import rescala.fullmv.NodeVersionHistory._

import scala.annotation.elidable.ASSERTION
import scala.annotation.{elidable, tailrec}
import scala.collection.mutable.ArrayBuffer

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

sealed trait FramingBranchResult[+T, +R]
object FramingBranchResult {
  case object FramingBranchEnd extends FramingBranchResult[Nothing, Nothing]
  case class Frame[T, R](out: Set[R], frame: T) extends FramingBranchResult[T, R]
  case class FrameSupersede[T, R](out: Set[R], frame: T, supersede: T) extends FramingBranchResult[T, R]
  case class Deframe[T, R](out: Set[R], deframe: T) extends FramingBranchResult[T, R]
  case class DeframeReframe[T, R](out: Set[R], deframe: T, reframe: T) extends FramingBranchResult[T, R]
}

sealed trait NotificationResultAction[+T, +R]
object NotificationResultAction {
  // upon notify:
  //    branch merge: T/F
  //    reev: wait/ready/unchanged/unchanged+FF/unchanged+next
  // upon reevOut:
  //    done/FF/next
  case object NotGlitchFreeReady extends NotificationResultAction[Nothing, Nothing]
  case object ResolvedNonFirstFrameToUnchanged extends NotificationResultAction[Nothing, Nothing]
  case object GlitchFreeReadyButQueued extends NotificationResultAction[Nothing, Nothing]
  case object GlitchFreeReady extends NotificationResultAction[Nothing, Nothing]
  sealed trait NotificationOutAndSuccessorOperation[+T, R] extends NotificationResultAction[T, R] {
    val out: Set[R]
  }
  object NotificationOutAndSuccessorOperation {
    case class NoSuccessor[R](out: Set[R]) extends NotificationOutAndSuccessorOperation[Nothing, R]
    case class FollowFraming[T, R](out: Set[R], succTxn: T) extends NotificationOutAndSuccessorOperation[T, R]
    case class NextReevaluation[T, R](out: Set[R], succTxn: T) extends NotificationOutAndSuccessorOperation[T, R]
  }
}

/**
  * A node version history datastructure
  * @param init the initial creating transaction
  * @param valuePersistency the value persistency descriptor
  * @tparam V the type of stored values
  * @tparam T the type of transactions
  * @tparam InDep the type of incoming dependency nodes
  * @tparam OutDep the type of outgoing dependency nodes
  */
class NodeVersionHistory[V, T <: FullMVTurn, InDep, OutDep](init: T, val valuePersistency: ValuePersistency[V]) extends FullMVState[V, T, InDep, OutDep] {
  class Version(val txn: T, var stable: Boolean, var out: Set[OutDep], var pending: Int, var changed: Int, var value: Option[V]) extends ManagedBlocker {
    // txn >= Executing, stable == true, node reevaluation completed changed
    def isWritten: Boolean = changed == 0 && value.isDefined
    // txn <= WrapUp, any following versions are stable == false
    def isFrame: Boolean = pending > 0 || changed > 0
    // isReadOrDynamic: has no implications really..
    def isReadOrDynamic: Boolean = pending == 0 && changed == 0 && value.isEmpty
    // isOvertakeCompensation: Will become isReadOrDynamic or isFrame once overtaken (no)change notifications have arrived.
    def isOvertakeCompensation: Boolean = pending < 0 || changed < 0

    // should only be used if isFrame == true is known (although it implies that)
    def isReadyForReevaluation: Boolean = pending == 0 && changed > 0
    // should only be used if txn >= Executing, as it may falsely return true in the case that a txn == Framing
    // had a frame converted into a marker due to frame superseding (or drop retrofitting?) and that frame was
    // marked stable after all preceding placeholders were removed but anoter txn2 == Framing inserts another
    // preceding frame which destabilizes this version again.
    def isFinal: Boolean = isWritten || (isReadOrDynamic && stable)

    def read(): V = {
      assert(isWritten, "reading un-written "+this)
      value.get
    }

    var finalWaiters: Int = 0
    var stableWaiters: Int = 0
    override def block(): Boolean = NodeVersionHistory.this.synchronized {
      isReleasable || {
        finalWaiters += 1
        NodeVersionHistory.this.wait()
        finalWaiters -= 1
        isReleasable }
    }

    override def isReleasable: Boolean = NodeVersionHistory.this.synchronized {
      isFinal
    }

    // common blocking case (now, dynamicDepend): Use self as blocker instance to reduce garbage
    def blockForFinal: ManagedBlocker = this

    // less common blocking case
    // fake lazy val without synchronization, because it is accessed only while the node's monitor is being held.
    private var _blockForStable: ManagedBlocker = null
    def blockForStable: ManagedBlocker = {
      if(_blockForStable == null) {
        _blockForStable = new ManagedBlocker {
          override def block(): Boolean = NodeVersionHistory.this.synchronized {
            isReleasable || {
              stableWaiters += 1
              NodeVersionHistory.this.wait()
              stableWaiters -= 1
              isReleasable }
          }
          override def isReleasable: Boolean = NodeVersionHistory.this.synchronized {
            stable
          }
        }
      }
      _blockForStable
    }


    override def toString: String = {
      if(isWritten){
        s"Written($txn, out=$out, v=${value.get})"
      } else if (isReadOrDynamic) {
        (if(stable) "Stable" else "Unstable") + s"Marker($txn, out=$out)"
      } else if (isOvertakeCompensation) {
        s"OvertakeCompensation($txn, ${if (stable) "stable" else "unstable"}, out=$out, pending=$pending, changed=$changed)"
      } else if(isFrame) {
        if(stable) {
          if(isReadyForReevaluation) {
            s"Active($txn, out=$out)"
          } else {
            s"FirstFrame($txn, out=$out, pending=$pending, changed=$changed)"
          }
        } else {
          if(isReadyForReevaluation) {
            s"Queued($txn, out=$out)"
          } else {
            s"Frame($txn, out=$out, pending=$pending, changed=$changed)"
          }
        }
      } else {
        "UnknownVersionCase!(" + txn + ", " + (if(stable) "stable" else "unstable") + ", out=" + out + ", pending=" + pending + ", changed=" + changed + ", value = " + value + ")"
      }
    }
  }

  @elidable(ASSERTION) @inline
  def assertOptimizationsIntegrity(debugOutputDescription: => String): Unit = {
    def debugStatement(whatsWrong: String): String = s"$debugOutputDescription left $whatsWrong in $this"
    assert(size <= _versions.length, debugStatement("size out of bounds"))
    assert(size > 0, debugStatement("version list empty"))
    assert(!_versions.take(size).contains(null), debugStatement("null version in bounds"))
    assert(!_versions.drop(size).exists(_ != null), debugStatement("non-null version outside bounds"))
    assert(_versions(0).isWritten, debugStatement("first version not written"))

    assert(firstFrame > 0, debugStatement("firstFrame out of bounds negative"))
    assert(firstFrame <= size, debugStatement("firstFrame out of bounds positive"))
    assert(firstFrame == size || _versions(firstFrame).isFrame, debugStatement("firstFrame not frame"))
    assert(!_versions.take(firstFrame).exists(_.isFrame), debugStatement("firstFrame not first"))

    assert(latestWritten >= 0, debugStatement("latestWritten out of bounds negative"))
    assert(latestReevOut >= latestWritten, debugStatement("latest reevout out of bounds negative"))
    assert(latestReevOut < size, debugStatement("latestWritten out of bounds positive"))
    assert(_versions(latestWritten).isWritten, debugStatement("latestWritten not written"))
    assert(!_versions.slice(latestWritten + 1, size).exists(_.isWritten), debugStatement("latestWritten not latest"))
    assert(_versions(latestReevOut).pending == 0 && _versions(latestReevOut).changed == 0 && _versions(latestReevOut).stable, "latestReevOut points to invalid version")

    assert(!_versions.take(size).zipWithIndex.exists{case (version, index) => version.stable != (index <= firstFrame)}, debugStatement("broken version stability"))

    assert(latestGChint >= 0, debugStatement("latestGChint out of bounds negative"))
    assert(!_versions.take(latestGChint).exists(_.txn.phase != TurnPhase.Completed), debugStatement("latestGChint has incomplete predecessor transaction"))
    for((first,second) <- _versions.zip(_versions.tail) if first != null && second != null) {
      assert(second.txn.isTransitivePredecessor(first.txn) || first.txn.phase == TurnPhase.Completed, debugStatement(s"${first.txn} not a predecessor of ${second.txn} but both have version ordered this way"))
      assert(!first.txn.isTransitivePredecessor(second.txn), debugStatement(s"${first.txn} has predecessor cycle with ${second.txn}"))
    }
  }

  override def toString: String = super.toString + s" -> size $size, latestWritten $latestWritten, latestReevOut $latestReevOut ${if(firstFrame > size) "no frames" else "firstFrame "+firstFrame}, latestGChint $latestGChint): \n  " + _versions.zipWithIndex.map{case (version, index) => s"$index: $version"}.mkString("\n  ")

  // =================== STORAGE ====================

  var _versions = new Array[Version](11)
  _versions(0) = new Version(init, stable = true, out = Set(), pending = 0, changed = 0, Some(valuePersistency.initialValue))
  var size = 1
  var latestValue: V = valuePersistency.initialValue

  private def createVersionInHole(position: Int, txn: T) = {
    val version = new Version(txn, stable = position <= firstFrame, _versions(position - 1).out, pending = 0, changed = 0, None)
    size += 1
    _versions(position) = version
    if (position <= firstFrame) firstFrame += 1
    if (position <= latestWritten) latestWritten += 1
    if (position <= latestReevOut) latestReevOut += 1
    version
  }

  // =================== NAVIGATION ====================
  var firstFrame: Int = size
  var latestWritten: Int = 0
  var latestReevOut: Int = 0
  var latestGChint: Int = 0

  /**
    * performs binary search for the given transaction in _versions. This establishes an order in sgt against all other
    * versions' transactions, placing the given transaction as late as possible with respect to transaction's current
    * phases. If asked, a new version is created at the found position. May expunge any number of obsolete versions.
    * @param lookFor the transaction to look for
    * @param from current from index of the search range
    * @param to current to index (exclusive) of the search range
    * @param versionRequired whether or not a version should be created, in case none exists for the given transaction
    * @return a tuple consisting of: (a) the index of the version associated with the given transaction, or if no such
    *         version exists and was not requested to be created, the negative index at which it would have to be
    *         inserted (b) how many versions were expunged for garbage collection. Note that we assume no insertion to
    *         ever occur at index 0 -- the the first version of any node is either from the transaction that created it
    *         or from some completed transaction. In the former case, no preceding transaction should be aware of the
    *         node's existence. In the latter case, there are no preceding transactions that still execute operations.
    *         Thus insertion at index 0 should be impossible. A return value of 0 thus means "found at 0", rather than
    *         "should insert at 0"
    */
  private def findOrPigeonHole(lookFor: T, from: Int, to: Int, versionRequired: Boolean): (Int, Int) = {
    assert(from <= to, s"binary search started with backwards indices from $from to $to")
    assert(to <= size, s"binary search upper bound $to beyond history size $size")
    assert(to == size || Set[PartialOrderResult](SecondFirstSameSCC, UnorderedSCCUnknown).contains(DecentralizedSGT.getOrder(_versions(to).txn, lookFor)), s"to = $to successor for non-blocking search of known static $lookFor pointed to version of ${_versions(to).txn} which is ordered earlier.")
    assert(DecentralizedSGT.getOrder(_versions(from - 1).txn, lookFor) != SecondFirstSameSCC, s"from - 1 = ${from - 1} predecessor for non-blocking search of $lookFor pointed to version of ${_versions(from - 1).txn} which is already ordered later.")
    assert(from > 0, s"binary search started with non-positive lower bound $from")

    val posOrInsert = findOrPigeonHoleNonblocking(lookFor, from, fromIsKnownPredecessor = false, to, knownSameSCC = false)

    assert(_versions(latestGChint).txn.phase == TurnPhase.Completed, s"binary search returned $posOrInsert for $lookFor with GC hint $latestGChint pointing to a non-completed transaction in $this")
    assert(latestGChint < math.abs(posOrInsert), s"binary search returned $posOrInsert for $lookFor inside garbage collected section (< $latestGChint) in $this")

    assert(posOrInsert < size, s"binary search returned found at $posOrInsert for $lookFor, which is out of bounds in $this")
    assert(posOrInsert < 0 || _versions(posOrInsert).txn == lookFor, s"binary search returned found at $posOrInsert for $lookFor, which is wrong in $this")
    assert(posOrInsert >= 0 || -posOrInsert <= size, s"binary search returned insert at ${-posOrInsert}, which is out of bounds in $this")
    assert(posOrInsert >= 0 || Set[PartialOrderResult](FirstFirstSameSCC, FirstFirstSCCUnkown).contains(DecentralizedSGT.getOrder(_versions(-posOrInsert - 1).txn, lookFor)), s"binary search returned insert at ${-posOrInsert} for $lookFor, but predecessor isn't ordered first in $this")
    assert(posOrInsert >= 0 || -posOrInsert == to || DecentralizedSGT.getOrder(_versions(-posOrInsert).txn, lookFor) == SecondFirstSameSCC, s"binary search returned insert at ${-posOrInsert} for $lookFor, but it isn't ordered before successor in $this")

    if(posOrInsert < 0 && versionRequired) {
      val gcd = arrangeVersionArray(-posOrInsert)
      val pos = -posOrInsert - gcd
      createVersionInHole(pos, lookFor)
      (pos, gcd)
    } else {
      (posOrInsert, 0)
    }
  }

  val DEFAULT_MIN_POS = 0
  /**
    * determine the position or insertion point for a framing transaction
    *
    * @param txn the transaction
    * @return the position (positive values) or insertion point (negative values)
    */
  private def getFramePositionFraming(txn: T, minPos: Int = DEFAULT_MIN_POS): (Int, Int) = {
    assert(minPos == DEFAULT_MIN_POS || minPos > math.max(latestGChint, latestReevOut), s"nonsensical minpos $minPos <= max(latestGChint $latestGChint, latestReevOut $latestReevOut)")
    val knownOrderedMinPosIsProvided = minPos != DEFAULT_MIN_POS
    val fromFinal = if (knownOrderedMinPosIsProvided) minPos else math.max(latestGChint, latestReevOut) + 1
    if(fromFinal == size) {
      ensureFromFinalRelationIsRecorded(size, txn, UnlockedUnknown).unlockedIfLocked()
      val gcd = arrangeVersionArray(size)
      val pos = size
      createVersionInHole(pos, txn)
      (pos, gcd)
    } else {
      val (insertOrFound, _) = findOrPigeonHoleFramingPredictive(txn, fromFinal, knownOrderedMinPosIsProvided, UnlockedUnknown)
      if(insertOrFound < 0) {
        val gcd = arrangeVersionArray(-insertOrFound)
        val pos = -insertOrFound - gcd
        createVersionInHole(pos, txn)
        (pos, gcd)
      } else {
        (insertOrFound, 0)
      }
    }
  }

  private def ensureFromFinalRelationIsRecorded(fromFinal: Int, txn: T, sccState: SCCState): SCCState = {
    val predToRecord = _versions(fromFinal - 1).txn
    assert(!predToRecord.isTransitivePredecessor(txn), s"$predToRecord was concurrently ordered after $txn although we assumed this to be impossible")
    if (predToRecord.phase == TurnPhase.Completed){
      sccState
    } else if(txn.isTransitivePredecessor(predToRecord)) {
      // TODO this case is redundant if ensureRelationIsRecorded eventually does tryLock/check spinning
      sccState.known
    } else {
      val lock = ensureRelationIsRecorded(predToRecord, txn, predToRecord, txn, sccState)
      lock.unlockedIfLocked()
    }
  }

  private def getFramePositionsFraming(one: T, two: T): (Int, Int) = {
    val res@(pOne, pTwo) = getFramePositionsFraming0(one, two)
    assert(_versions(pOne).txn == one, s"first position $pOne doesn't correspond to first transaction $one in $this")
    assert(_versions(pTwo).txn == two, s"second position $pTwo doesn't correspond to second transaction $two in $this")

    assert(_versions(pOne - 1).txn.phase == TurnPhase.Completed || one.isTransitivePredecessor(_versions(pOne - 1).txn), s"first $one isn't ordered after its predecessor ${_versions(pOne - 1).txn} in $this")
    assert(_versions(pOne + 1).txn.isTransitivePredecessor(one), s"first $one isn't ordered before its successor ${_versions(pOne + 1).txn}")
    assert(_versions(pTwo - 1).txn.phase == TurnPhase.Completed || two.isTransitivePredecessor(_versions(pTwo - 1).txn), s"second $two isn't ordered after its predecessor ${_versions(pTwo - 1).txn}")
    assert(pTwo + 1 == size || _versions(pTwo + 1).txn.isTransitivePredecessor(two), s"second $two isn't ordered before its successor ${_versions(pTwo + 1).txn}")
    res
  }

  private def getFramePositionsFraming0(one: T, two: T): (Int, Int) = {
    val fromFinal = math.max(latestGChint, latestReevOut) + 1
    if(fromFinal == size) {
      // shortcut: insert both versions at the end
      ensureFromFinalRelationIsRecorded(size, one, UnlockedUnknown).unlockedIfLocked()
      if(size + 2 > _versions.length) arrangeVersionArray(size, size)
      val out = _versions(size - 1).out
      val stable = firstFrame == size
      val first = size
      _versions(first) = new Version(one, stable, out, pending = 0, changed = 0, value = None)
      val second = size + 1
      _versions(second) = new Version(two, stable, out, pending = 0, changed = 0, value = None)
      size += 2
      if(stable) firstFrame += 2
      (first, second)
    } else {
      val (insertOrFoundOne, sccState) = findOrPigeonHoleFramingPredictive(one, fromFinal, fromFinalPredecessorRelationIsRecorded = false, UnlockedUnknown)
      if(insertOrFoundOne >= 0) {
        // first one found: defer to just look for the second alone
        val (insertOrFoundTwo, gcd) = getFramePositionFraming(two, insertOrFoundOne + 1)
        (insertOrFoundOne - gcd, insertOrFoundTwo)
      } else {
        // first one not found:
        val insertOne = -insertOrFoundOne
        if (insertOne == size) {
          val gcd = arrangeVersionArray(insertOne, size)
          val first = insertOne - gcd
          createVersionInHole(first, one)
          val second = size
          createVersionInHole(second, two)
          (first, second)
        } else {
          val (insertOrFoundTwo, _) = findOrPigeonHoleFramingPredictive(two, insertOne, fromFinalPredecessorRelationIsRecorded = true, sccState)
          if (insertOrFoundTwo >= 0) {
            val gcd = arrangeVersionArray(insertOne)
            val first = insertOne - gcd
            createVersionInHole(first, one)
            (first, insertOrFoundTwo - gcd + 1)
          } else {
            val insertTwo = -insertOrFoundTwo
            val gcd = arrangeVersionArray(insertOne, insertTwo)
            val first = insertOne - gcd
            val second = insertTwo - gcd + 1
            createVersionInHole(first, one)
            createVersionInHole(second, two)
            (first, second)
          }
        }
      }
    }
  }

  private def getFramePositionPropagating(txn: T, minPos: Int = firstFrame): (Int, Int) = {
    assert(minPos >= firstFrame, s"nonsensical minpos $minPos < firstFrame $firstFrame")
    assert(firstFrame < size, s"a propagating turn may not have a version when looking for a frame, but there must be *some* frame.")
    if(minPos == size) {
      assert(txn.isTransitivePredecessor(_versions(minPos - 1).txn), s"knownOrderedMinPos $minPos for $txn: predecessor ${_versions(minPos - 1).txn} not ordered")
      val gcd = arrangeVersionArray(minPos)
      val pos = minPos - gcd
      createVersionInHole(pos, txn)
      (pos, gcd)
    } else if (_versions(minPos).txn == txn) {
      // common-case shortcut attempt: receive notification for firstFrame
      (minPos, 0)
    } else {
      assert(txn.isTransitivePredecessor(_versions(if(minPos == firstFrame) firstFrame else minPos - 1).txn), s"either $txn at manually supplied knownOrderedMinPos $minPos was not ordered properly, or it already has a frame here and is thus ordered behind the firstFrame $firstFrame, or it overtook its partial framing, in which case it is ordered against ${_versions(firstFrame).txn} on another node (i hope this is right) in $this")
      val (insertOrFound, _) = findOrPigeonHolePropagatingPredictive(txn, minPos, fromFinalPredecessorRelationIsRecorded = true, minPos, size, size, toFinalRelationIsRecorded = true, UnlockedUnknown)
      if (insertOrFound < 0) {
        val gcd = arrangeVersionArray(-insertOrFound)
        val pos = -insertOrFound - gcd
        createVersionInHole(pos, txn)
        (pos, gcd)
      } else {
        (insertOrFound, 0)
      }
    }
  }

  private def getFramePositionsPropagating(one: T, two: T): (Int, Int) = {
    assert(firstFrame < size, s"a propagating turn may not have a version when looking for a frame, but there must be *some* frame.")
    if (_versions(firstFrame).txn == one) {
      // common-case shortcut attempt: receive notification for firstFrame
      val foundOne = firstFrame
      val (insertOrFoundTwo, gcd) = getFramePositionPropagating(two, firstFrame + 1)
      (foundOne - gcd, insertOrFoundTwo)
    } else {
      val (insertOrFoundOne, sccState) = findOrPigeonHolePropagatingPredictive(one, firstFrame, fromFinalPredecessorRelationIsRecorded = false, firstFrame, size, size, toFinalRelationIsRecorded = true, UnlockedUnknown)
      if(insertOrFoundOne >= 0) {
        // first one found: defer to just look for the second alone
        val (insertOrFoundTwo, gcd) = getFramePositionPropagating(two, insertOrFoundOne + 1)
        (insertOrFoundOne - gcd, insertOrFoundTwo)
      } else {
        // first one not found:
        val insertOne = -insertOrFoundOne
        if (insertOne == size) {
          val gcd = arrangeVersionArray(insertOne, size)
          val first = insertOne - gcd
          createVersionInHole(first, one)
          val second = size
          createVersionInHole(second, two)
          (first, second)
        } else {
          val (insertOrFoundTwo, _) = findOrPigeonHolePropagatingPredictive(two, insertOne, fromFinalPredecessorRelationIsRecorded = true, insertOne, size, size, toFinalRelationIsRecorded = true, sccState)
          if (insertOrFoundTwo >= 0) {
            val gcd = arrangeVersionArray(insertOne)
            val first = insertOne - gcd
            createVersionInHole(first, one)
            (first, insertOrFoundTwo - gcd + 1)
          } else {
            val insertTwo = -insertOrFoundTwo
            val gcd = arrangeVersionArray(insertOne, insertTwo)
            val first = insertOne - gcd
            val second = insertTwo - gcd + 1
            createVersionInHole(first, one)
            createVersionInHole(second, two)
            (first, second)
          }
        }
      }
    }
  }
  @tailrec
  private def findOrPigeonHoleNonblocking(lookFor: T, from: Int, fromIsKnownPredecessor: Boolean, to: Int, knownSameSCC: Boolean): Int = {
    if (to == from) {
      if(!fromIsKnownPredecessor) {
        val pred = _versions(from - 1).txn
        val unblockedOrder = DecentralizedSGT.getOrder(pred, lookFor)
        assert(unblockedOrder != SecondFirstSameSCC)
        if(unblockedOrder == UnorderedSCCUnknown) {
          val lock = DecentralizedSGT.acquireLock(pred, lookFor, if(knownSameSCC) UnlockedSameSCC else UnlockedUnknown)
          try {
            val establishedOrder = DecentralizedSGT.ensureOrder(pred, lookFor)
            assert(establishedOrder == FirstFirst)
          } finally {
            lock.unlock()
          }
        }
      }
      -from
    } else {
      val idx = from+(to-from-1)/2
      val candidate = _versions(idx).txn
      if(candidate.phase == TurnPhase.Completed) latestGChint = idx
      if(candidate == lookFor) {
        idx
      } else DecentralizedSGT.getOrder(candidate, lookFor) match {
        case FirstFirstSCCUnkown =>
          findOrPigeonHoleNonblocking(lookFor, idx + 1, fromIsKnownPredecessor = true, to, knownSameSCC)
        case FirstFirstSameSCC =>
          findOrPigeonHoleNonblocking(lookFor, idx + 1, fromIsKnownPredecessor = true, to, knownSameSCC = true)
        case SecondFirstSameSCC =>
          findOrPigeonHoleNonblocking(lookFor, from, fromIsKnownPredecessor, idx, knownSameSCC = true)
        case UnorderedSCCUnknown =>
          val lock = DecentralizedSGT.acquireLock(candidate, lookFor, if(knownSameSCC) UnlockedSameSCC else UnlockedUnknown)
          try {
            findOrPigeonHoleLocked(lookFor, from, fromIsKnownPredecessor, to)
          } finally {
            lock.unlock()
          }
      }
    }
  }

  @tailrec
  private def findOrPigeonHoleLocked(lookFor: T, from: Int, fromKnownOrdered: Boolean, to: Int): Int = {
    if (to == from) {
      if(!fromKnownOrdered) {
        val pred = _versions(from - 1).txn
        val establishedOrder = DecentralizedSGT.ensureOrder(pred, lookFor)
        assert(establishedOrder == FirstFirst)
      }
      -from
    } else {
      val idx = from+(to-from-1)/2
      val candidate = _versions(idx).txn
      if(candidate.phase == TurnPhase.Completed) latestGChint = idx
      if(candidate == lookFor) {
        idx
      } else DecentralizedSGT.ensureOrder(candidate, lookFor) match {
        case FirstFirst =>
          findOrPigeonHoleLocked(lookFor, idx + 1, fromKnownOrdered = true, to)
        case SecondFirst =>
          findOrPigeonHoleLocked(lookFor, from, fromKnownOrdered, idx)
      }
    }
  }

  private def findOrPigeonHoleFramingPredictive(lookFor: T, fromFinal: Int, fromFinalPredecessorRelationIsRecorded: Boolean, sccState: SCCConnectivity): (Int, SCCConnectivity) = {
    assert(fromFinal <= size, s"binary search started with backwards indices from $fromFinal to $size")
    assert(size <= size, s"binary search upper bound $size beyond history size $size")
    assert(DecentralizedSGT.getOrder(_versions(fromFinal - 1).txn, lookFor) != SecondFirstSameSCC, s"from - 1 = ${fromFinal - 1} predecessor for non-blocking search of $lookFor pointed to version of ${_versions(fromFinal - 1).txn} which is already ordered later.")
    assert(fromFinal > 0, s"binary search started with non-positive lower bound $fromFinal")

    val r@(posOrInsert, _) = findOrPigeonHoleFramingPredictive0(lookFor: T, fromFinal: Int, fromFinalPredecessorRelationIsRecorded: Boolean, math.max(fromFinal, size - 1), size: Int, sccState: SCCConnectivity)

    assert(_versions(latestGChint).txn.phase == TurnPhase.Completed, s"binary search returned $posOrInsert for $lookFor with GC hint $latestGChint pointing to a non-completed transaction in $this")
    assert(latestGChint < math.abs(posOrInsert), s"binary search returned $posOrInsert for $lookFor inside garbage collected section (< $latestGChint) in $this")

    assert(posOrInsert < size, s"binary search returned found at $posOrInsert for $lookFor, which is out of bounds in $this")
    assert(posOrInsert < 0 || _versions(posOrInsert).txn == lookFor, s"binary search returned found at $posOrInsert for $lookFor, which is wrong in $this")
    assert(posOrInsert >= 0 || -posOrInsert <= size, s"binary search returned insert at ${-posOrInsert}, which is out of bounds in $this")
    assert(posOrInsert >= 0 || lookFor.isTransitivePredecessor(_versions(-posOrInsert - 1).txn) || _versions(-posOrInsert - 1).txn.phase == TurnPhase.Completed, s"binary search returned insert at ${-posOrInsert} for $lookFor, but predecessor neither ordered first nor completed in $this")
    assert(posOrInsert >= 0 || -posOrInsert == size || _versions(-posOrInsert).txn.isTransitivePredecessor(lookFor), s"binary search returned insert at ${-posOrInsert} for $lookFor, but it isn't ordered before successor in $this")
    assert(posOrInsert >= 0 || -posOrInsert == size || _versions(-posOrInsert).txn.phase == TurnPhase.Framing, s"binary search returned insert at ${-posOrInsert} for $lookFor, but successor has already passed framing in $this")

    r
  }

  @tailrec
  private def findOrPigeonHoleFramingPredictive0(lookFor: T, fromFinal: Int, fromFinalPredecessorRelationIsRecorded: Boolean, fromSpeculative: Int, toFinal: Int, sccState: SCCConnectivity): (Int, SCCConnectivity) = {
    if(fromSpeculative == toFinal) {
      val (fromOrderedSuccessfully, changedSCCState) = tryOrderFromFraming(lookFor, fromFinal, fromFinalPredecessorRelationIsRecorded, fromSpeculative, sccState)
      val unlocked = changedSCCState.unlockedIfLocked()
      if(fromOrderedSuccessfully == Succeeded) {
        (-fromSpeculative, UnlockedSameSCC)
      } else {
        assert(unlocked == UnlockedSameSCC, s"establishing from relationship failed, but $lookFor is supposedly not in same SCC")
        findOrPigeonHoleFramingPredictive0(lookFor, fromFinal, fromFinalPredecessorRelationIsRecorded, fromFinal - 1, fromSpeculative, UnlockedSameSCC)
      }
    } else {
      val probe = fromSpeculative+(toFinal-fromSpeculative-1)/2
      val candidate = _versions(probe).txn
      if(candidate == lookFor) {
        (probe, sccState.known)
      } else {
        candidate.phase match {
          case TurnPhase.Completed =>
            latestGChint = probe
            findOrPigeonHoleFramingPredictive0(lookFor, probe + 1, fromFinalPredecessorRelationIsRecorded = true, probe + 1, toFinal, sccState)
          case TurnPhase.Executing =>
            assert(!candidate.isTransitivePredecessor(lookFor), s"framing $lookFor should not be predecessor of some executing $candidate")
            findOrPigeonHoleFramingPredictive0(lookFor, probe + 1, fromFinalPredecessorRelationIsRecorded = false, probe + 1, toFinal, sccState)
          case TurnPhase.Framing =>
            if (lookFor.isTransitivePredecessor(candidate)) {
              findOrPigeonHoleFramingPredictive0(lookFor, probe + 1, fromFinalPredecessorRelationIsRecorded = true, probe + 1, toFinal, sccState.known)
            } else if (candidate.isTransitivePredecessor(lookFor)) {
              findOrPigeonHoleFramingPredictive0(lookFor, fromFinal, fromFinalPredecessorRelationIsRecorded, fromSpeculative, probe, sccState.known)
            } else {
              findOrPigeonHoleFramingPredictive0(lookFor, fromFinal, fromFinalPredecessorRelationIsRecorded, probe + 1, toFinal, sccState)
            }
          case unknown =>
            throw new AssertionError(s"$candidate has unknown phase $unknown")
        }
      }
    }
  }

  private def findOrPigeonHolePropagatingPredictive(lookFor: T, fromFinal: Int, fromFinalPredecessorRelationIsRecorded: Boolean, fromSpeculative: Int, toSpeculative: Int, toFinal: Int, toFinalRelationIsRecorded: Boolean, sccState: SCCConnectivity): (Int, SCCConnectivity) = {
    assert(fromFinal <= toFinal, s"binary search started with backwards indices from $fromFinal to $toFinal")
    assert(toFinal <= size, s"binary search upper bound $toFinal beyond history size $size")
    assert(toFinal == size || Set[PartialOrderResult](SecondFirstSameSCC, UnorderedSCCUnknown).contains(DecentralizedSGT.getOrder(_versions(toFinal).txn, lookFor)), s"to = $toFinal successor for non-blocking search of known static $lookFor pointed to version of ${_versions(toFinal).txn} which is ordered earlier.")
    assert(DecentralizedSGT.getOrder(_versions(fromFinal - 1).txn, lookFor) != SecondFirstSameSCC, s"from - 1 = ${fromFinal - 1} predecessor for non-blocking search of $lookFor pointed to version of ${_versions(fromFinal - 1).txn} which is already ordered later.")
    assert(fromFinal > 0, s"binary search started with non-positive lower bound $fromFinal")

    val r@(posOrInsert, _) = findOrPigeonHolePropagatingPredictive0(lookFor: T, fromFinal: Int, fromFinalPredecessorRelationIsRecorded: Boolean, fromSpeculative: Int, toSpeculative: Int, toFinal: Int, toFinalRelationIsRecorded: Boolean, sccState: SCCConnectivity)

    assert(_versions(latestGChint).txn.phase == TurnPhase.Completed, s"binary search returned $posOrInsert for $lookFor with GC hint $latestGChint pointing to a non-completed transaction in $this")
    assert(latestGChint < math.abs(posOrInsert), s"binary search returned $posOrInsert for $lookFor inside garbage collected section (< $latestGChint) in $this")

    assert(posOrInsert < size, s"binary search returned found at $posOrInsert for $lookFor, which is out of bounds in $this")
    assert(posOrInsert < 0 || _versions(posOrInsert).txn == lookFor, s"binary search returned found at $posOrInsert for $lookFor, which is wrong in $this")
    assert(posOrInsert >= 0 || -posOrInsert <= size, s"binary search returned insert at ${-posOrInsert}, which is out of bounds in $this")
    assert(posOrInsert >= 0 || Set[PartialOrderResult](FirstFirstSameSCC, FirstFirstSCCUnkown).contains(DecentralizedSGT.getOrder(_versions(-posOrInsert - 1).txn, lookFor)), s"binary search returned insert at ${-posOrInsert} for $lookFor, but predecessor isn't ordered first in $this")
    assert(posOrInsert >= 0 || -posOrInsert == toFinal || DecentralizedSGT.getOrder(_versions(-posOrInsert).txn, lookFor) == SecondFirstSameSCC, s"binary search returned insert at ${-posOrInsert} for $lookFor, but it isn't ordered before successor in $this")

    r
  }

  @tailrec
  private def findOrPigeonHolePropagatingPredictive0(lookFor: T, fromFinal: Int, fromFinalPredecessorRelationIsRecorded: Boolean, fromSpeculative: Int, toSpeculative: Int, toFinal: Int, toFinalRelationIsRecorded: Boolean, sccState: SCCConnectivity): (Int, SCCConnectivity) = {
    if(fromSpeculative == toSpeculative) {
      val (fromOrderedResult, asdasd) = tryOrderFromPropagating(lookFor, fromFinal, fromFinalPredecessorRelationIsRecorded, fromSpeculative, sccState)
      val changedSCCState = asdasd.unlockedIfLocked() // TODO this may not be needed once the old binary search is gone
      fromOrderedResult match {
        case Succeeded =>
          val (toOrderedSuccessfully, againChangedSCCState) = tryOrderToPropagating(lookFor, toSpeculative, toFinal, toFinalRelationIsRecorded, changedSCCState)
          val unlocked = againChangedSCCState.unlockedIfLocked()
          if(toOrderedSuccessfully == Succeeded) {
            (-fromSpeculative, unlocked.known)
          } else {
            findOrPigeonHolePropagatingPredictive0(lookFor, toSpeculative, fromFinalPredecessorRelationIsRecorded = true, toSpeculative, toFinal, toFinal, toFinalRelationIsRecorded, unlocked)
          }
        case FailedFinalAndRecorded =>
          val unlocked = changedSCCState.unlockedIfLocked()
          findOrPigeonHolePropagatingPredictive0(lookFor, fromFinal, fromFinalPredecessorRelationIsRecorded, fromFinal, fromSpeculative - 1, fromSpeculative - 1, toFinalRelationIsRecorded = true, unlocked)
        case FailedNonfinal =>
          // This case is a bit of a weird one. The following events occurred:
          // an operation such as notifyFollowFrame(X, lookFor) was executed.
          // It searched for the position of lookFor, which at that time was Framing.
          // It encountered a version of Y, which was also framing, and took it as lower bound.
          // Concurrently, Z transitioned to Executing
          // Then, the current termination attempt occurred.
          // It attempted to record Y < lookFor, but that failed because Y is still Framing, but lookFor now Executing and thus must go earlier.
          // Now we are here.
          // Now because lookFor must go before Y, we use the position of Y as the new exclusive upper bound for the fallback search.
          // In the future, though, Y may also transition to Executing.
          // Once that happened, a different Task also involving lookFor may concurrently establish the originally attempted order of Y < lookFor.
          // Thus, we must keep the previous toFinal bound, and can use Y only for speculation.
          // (Lastly, for concerned readers, this loop terminates because when the fallback search itself then fails, it uses Y as new _final_ lower Bound, so the final range is guaranteed to shrink before repeating.)
          val unlocked = changedSCCState.unlockedIfLocked()
          findOrPigeonHolePropagatingPredictive0(lookFor, fromFinal, fromFinalPredecessorRelationIsRecorded, fromFinal, fromSpeculative - 1, toFinal, toFinalRelationIsRecorded, unlocked)
      }
    } else {
      val probe = fromSpeculative+(toSpeculative-fromSpeculative-1)/2
      val candidate = _versions(probe).txn
      if(candidate == lookFor) {
        (probe, sccState)
      } else {
        candidate.phase match {
          case TurnPhase.Completed =>
            latestGChint = probe
            findOrPigeonHolePropagatingPredictive0(lookFor, probe + 1, fromFinalPredecessorRelationIsRecorded = true, probe + 1, toSpeculative, toFinal, toFinalRelationIsRecorded, sccState)
          case otherwise =>
            if (lookFor.isTransitivePredecessor(candidate)) {
              findOrPigeonHolePropagatingPredictive0(lookFor, probe + 1, fromFinalPredecessorRelationIsRecorded = true, probe + 1, toSpeculative, toFinal, toFinalRelationIsRecorded, sccState.known)
            } else if (candidate.isTransitivePredecessor(lookFor)) {
              findOrPigeonHolePropagatingPredictive0(lookFor, fromFinal, fromFinalPredecessorRelationIsRecorded, fromSpeculative, probe, probe, toFinalRelationIsRecorded = true, sccState.known)
            } else if(lookFor.phase <= otherwise) {
              findOrPigeonHolePropagatingPredictive0(lookFor, fromFinal, fromFinalPredecessorRelationIsRecorded, probe + 1, toSpeculative, toFinal, toFinalRelationIsRecorded, sccState)
            } else {
              findOrPigeonHolePropagatingPredictive0(lookFor, fromFinal, fromFinalPredecessorRelationIsRecorded, fromSpeculative, probe, toFinal, toFinalRelationIsRecorded, sccState)
            }
        }
      }
    }
  }

  // there is no tryOrderToFraming because framing turns always try to order themselves at the end
  private def tryOrderFromFraming(lookFor: T, fromFinal: Int, fromFinalPredecessorRelationIsRecorded: Boolean, fromSpeculative: Int, sccState: SCCState): (TryRecordResult, SCCState) = {
    if (fromSpeculative > fromFinal) {
      val predToRecord = _versions(fromSpeculative - 1).txn
      if (predToRecord.phase == TurnPhase.Completed) {
        // last chance to skip recording effort if predecessor completed concurrently
        (Succeeded, sccState)
      } else {
        tryRecordRelationship(predToRecord, lookFor, predToRecord, lookFor, sccState)
      }
    } else if (!fromFinalPredecessorRelationIsRecorded) {
      assert(fromFinal == fromSpeculative, s"someone speculated fromSpeculative=$fromSpeculative smaller than fromFinal=$fromFinal")
      (Succeeded, ensureFromFinalRelationIsRecorded(fromFinal, lookFor, sccState))
    } else {
      // there is no from to order
      (Succeeded, sccState)
    }
  }

  private def tryOrderFromPropagating(lookFor: T, fromFinal: Int, fromFinalPredecessorRelationIsRecorded: Boolean, fromSpeculative: Int, sccState: SCCState): (TryOrderResult, SCCState) = {
    if (fromSpeculative > fromFinal) {
      val predToRecord = _versions(fromSpeculative - 1).txn
      predToRecord.phase match {
        case TurnPhase.Completed =>
          // last chance to skip recording effort if predecessor completed concurrently
          (Succeeded, sccState)
        case TurnPhase.Executing =>
          tryRecordRelationship(predToRecord, lookFor, predToRecord, lookFor, sccState)
        case TurnPhase.Framing =>
          lookFor.acquirePhaseLockIfAtMost(TurnPhase.Framing) match {
            case TurnPhase.Framing =>
              try {
                tryRecordRelationship(predToRecord, lookFor, predToRecord, lookFor, sccState)
              } finally {
                lookFor.asyncReleasePhaseLock()
              }
            case TurnPhase.Executing =>
              // race conflict: lookFor was Framing earlier and ordered itself behind predToRecord, but lookFor
              (FailedNonfinal, sccState)
            case TurnPhase.Completed => throw new AssertionError(s"lookFor should not be able to complete concurrently")
            case unknown => throw new AssertionError(s"$lookFor unknown phase $unknown")
          }
        case unknown => throw new AssertionError(s"$predToRecord unknown phase $unknown")
      }
    } else if (!fromFinalPredecessorRelationIsRecorded) {
      assert(fromFinal == fromSpeculative, s"someone speculated fromSpeculative=$fromSpeculative smaller than fromFinal=$fromFinal")
      (Succeeded, ensureFromFinalRelationIsRecorded(fromFinal, lookFor, sccState))
    } else {
      // there is no from to order
      (Succeeded, sccState)
    }
  }

  private def tryOrderToPropagating(lookFor: T, toSpeculative: Int, toFinal: Int, toFinalRelationIsRecorded: Boolean, sccState: SCCState): (TryRecordResult, SCCState) = {
    if (toSpeculative < toFinal) {
      assert(lookFor.phase == TurnPhase.Executing, s"$lookFor has a speculative successor, which should not happen while it's still framing.")
      val succToRecord = _versions(toSpeculative).txn
      succToRecord.acquirePhaseLockIfAtMost(TurnPhase.Executing) match {
        case TurnPhase.Completed =>
          (Succeeded, sccState)
        case TurnPhase.Executing =>
          // could also acquirePhaseLockIfAtMost(TurnPhase.Framing) and default this case to (false, sccState), since
          // succToRecord must have been framing to have been made toSpeculative earlier, but now clearly isn't anymore
          // and thus the previous decision is no longer valid.
          // this might in turn allow simplifications for turn phase switching as only the transition framing->executing
          // may have new predecessors pushed before it concurrently, but once a turn is executing, only his own thread
          // would be modifying its predecessors.
          try {
            tryRecordRelationship(lookFor, succToRecord, succToRecord, lookFor, sccState)
          } finally {
            succToRecord.asyncReleasePhaseLock()
          }
        case TurnPhase.Framing =>
          try {
            val lock = ensureRelationIsRecorded(lookFor, succToRecord, succToRecord, lookFor, sccState)
            (Succeeded, lock)
          } finally {
            succToRecord.asyncReleasePhaseLock()
          }
        case unknown =>
          succToRecord.asyncReleasePhaseLock()
          throw new AssertionError(s"$succToRecord has unknown phase $unknown")
      }
    } else if (!toFinalRelationIsRecorded) {
      assert(toFinal == toSpeculative, s"someone speculated toSpeculative=$toSpeculative as larger than toFinal=$toFinal")
      assert(lookFor.phase == TurnPhase.Executing, s"$lookFor should only have a final but unrecorded to-relation if does a static read, i.e., is executing.")
      val succToRecord = _versions(toFinal).txn
      // while this assertion held when toFinal was decided, it may no longer be valid here, e.g. for a follow framing, which may have switched to Executing since then
      // assert(succToRecord.phase == TurnPhase.Framing, s"$succToRecord should only be a final but unrecorded to-relation that has framed a node, but not the static successor where $lookFor came from and thus not completed framing")
      val lock = ensureRelationIsRecorded(lookFor, succToRecord, succToRecord, lookFor, sccState)
      (Succeeded, lock)
    } else {
      // there is no to to order
      (Succeeded, sccState)
    }
  }


  private def tryRecordRelationship(attemptPredecessor: T, succToRecord: T, defender: T, contender: T, sccState: SCCState): (TryRecordResult, SCCState) = {
    val lock = DecentralizedSGT.acquireLock(defender, contender, sccState)
    if (attemptPredecessor.isTransitivePredecessor(succToRecord)) {
      (FailedFinalAndRecorded, lock)
    } else {
      if (!succToRecord.isTransitivePredecessor(attemptPredecessor)) succToRecord.addPredecessor(attemptPredecessor.selfNode)
      (Succeeded, lock)
    }
  }

  private def ensureRelationIsRecorded(predecessor: T, successor: T, defender: T, contender: T, sccState: SCCState): SCCState = {
    val lock = DecentralizedSGT.acquireLock(defender, contender, sccState)
    if (!successor.isTransitivePredecessor(predecessor)) successor.addPredecessor(predecessor.selfNode)
    lock
  }

  /**
    * determine the position or insertion point for a transaction for which
    * this node is known to have become final
    *
    * @param txn the transaction
    * @return the position (positive values) or insertion point (negative values)
    */
  private def findFinalPosition/*Propagating*/(txn: T, versionRequired: Boolean): (Int, Int) = {
    if(_versions(latestReevOut).txn == txn) {
      // common-case shortcut attempt: read latest completed reevaluation
      (latestReevOut, 0)
    } else if(latestWritten != latestReevOut && _versions(latestWritten).txn == txn)
      // common-case shortcut attempt: read latest changed reevaluation
      (latestWritten, 0)
    else
      findOrPigeonHole(txn, latestGChint + 1, firstFrame, versionRequired)
  }


  // =================== FRAMING ====================

  /**
    * entry point for regular framing
    *
    * @param txn the transaction visiting the node for framing
    */
  override def incrementFrame(txn: T): FramingBranchResult[T, OutDep] = synchronized {
    val result = incrementFrame0(txn, getFramePositionFraming(txn)._1)
    assertOptimizationsIntegrity(s"incrementFrame($txn) -> $result")
    result
  }

  /**
    * entry point for superseding framing
    * @param txn the transaction visiting the node for framing
    * @param supersede the transaction whose frame was superseded by the visiting transaction at the previous node
    */
  override def incrementSupersedeFrame(txn: T, supersede: T): FramingBranchResult[T, OutDep] = synchronized {
    val (position, supersedePos) = getFramePositionsFraming(txn, supersede)
    val version = _versions(position)
    version.pending += 1
    val result = if(position < firstFrame && _versions(position).pending == 1) {
      _versions(supersedePos).pending -= 1
      incrementFrameResultAfterNewFirstFrameWasCreated(txn, position)
    } else {
      decrementFrame0(supersede, supersedePos)
    }
    assertOptimizationsIntegrity(s"incrementSupersedeFrame($txn, $supersede) -> $result")
    result
  }

  override def decrementFrame(txn: T): FramingBranchResult[T, OutDep] = synchronized {
    val result = decrementFrame0(txn, getFramePositionFraming(txn)._1)
    assertOptimizationsIntegrity(s"decrementFrame($txn) -> $result")
    result
  }

  override def decrementReframe(txn: T, reframe: T): FramingBranchResult[T, OutDep] = synchronized {
    val (position, reframePos) = getFramePositionsFraming(txn, reframe)
    val version = _versions(position)
    version.pending += -1
    val result = if(position == firstFrame && version.pending == 0) {
      _versions(reframePos).pending += 1
      deframeResultAfterPreviousFirstFrameWasRemoved(txn, position)
    } else {
      incrementFrame0(reframe, reframePos)
    }
    assertOptimizationsIntegrity(s"deframeReframe($txn, $reframe) -> $result")
    result
  }

  private def incrementFrame0(txn: T, position: Int): FramingBranchResult[T, OutDep] = {
    val version = _versions(position)
    version.pending += 1
    if (position < firstFrame && version.pending == 1) {
      incrementFrameResultAfterNewFirstFrameWasCreated(txn, position)
    } else {
      FramingBranchResult.FramingBranchEnd
    }
  }

  private def decrementFrame0(txn: T, position: Int): FramingBranchResult[T, OutDep] = {
    val version = _versions(position)
    version.pending -= 1
    if (position == firstFrame && version.pending == 0) {
      deframeResultAfterPreviousFirstFrameWasRemoved(txn, position)
    } else {
      FramingBranchResult.FramingBranchEnd
    }
  }

  @tailrec private def destabilizeBackwardsUntilFrame(): Unit = {
    if(firstFrame < size) {
      val version = _versions(firstFrame)
      assert(version.stable, s"cannot destabilize $firstFrame: $version")
      version.stable = false
    }
    firstFrame -= 1
    if(!_versions(firstFrame).isFrame) destabilizeBackwardsUntilFrame()
  }

  private def incrementFrameResultAfterNewFirstFrameWasCreated(txn: T, position: Int) = {
    val previousFirstFrame = firstFrame
    destabilizeBackwardsUntilFrame()
    assert(firstFrame == position, s"destablizeBackwards did not reach $position: ${_versions(position)} but stopped at $firstFrame: ${_versions(firstFrame)}")

    if(previousFirstFrame < size) {
      FramingBranchResult.FrameSupersede(_versions(position).out, txn, _versions(previousFirstFrame).txn)
    } else {
      FramingBranchResult.Frame(_versions(position).out, txn)
    }
  }

  private def stabilizeForwardsUntilFrame(): Boolean = {
    @tailrec @inline def stabilizeForwardsUntilFrame0(encounteredWaiter: Boolean): Boolean = {
      firstFrame += 1
      if (firstFrame < size) {
        val version = _versions(firstFrame)
        assert(!version.stable, s"cannot stabilize $firstFrame: $version")
        version.stable = true
        val updatedEncounteredWaiters = encounteredWaiter || version.stableWaiters > 0
        if (!version.isFrame) {
          stabilizeForwardsUntilFrame0(updatedEncounteredWaiters || version.finalWaiters > 0)
        } else {
          updatedEncounteredWaiters
        }
      } else {
        encounteredWaiter
      }
    }
    stabilizeForwardsUntilFrame0(_versions(firstFrame).finalWaiters > 0)
  }

  private def deframeResultAfterPreviousFirstFrameWasRemoved(txn: T, position: Int) = {
    val encounteredWaiters = stabilizeForwardsUntilFrame()
    assert(!encounteredWaiters, "someone was waiting for a version by a framing transaction, but only executing transactions should perform waiting and they should never see framing transactions' versions.")

    if(firstFrame < size) {
      FramingBranchResult.DeframeReframe(_versions(position).out, txn, _versions(firstFrame).txn)
    } else {
      FramingBranchResult.Deframe(_versions(position).out, txn)
    }
  }

  /*
   * =================== NOTIFICATIONS/ / REEVALUATION ====================
   */

  /**
    * entry point for change/nochange notification reception
    * @param txn the transaction sending the notification
    * @param changed whether or not the dependency changed
    */
  override def notify(txn: T, changed: Boolean): NotificationResultAction[T, OutDep] = synchronized {
    val result = notify0(getFramePositionPropagating(txn)._1, txn, changed)
    assertOptimizationsIntegrity(s"notify($txn, $changed) -> $result")
    result
  }

  /**
    * entry point for change/nochange notification reception with follow-up framing
    * @param txn the transaction sending the notification
    * @param changed whether or not the dependency changed
    * @param followFrame a transaction for which to create a subsequent frame, furthering its partial framing.
    */
  override def notifyFollowFrame(txn: T, changed: Boolean, followFrame: T): NotificationResultAction[T, OutDep] = synchronized {
    val (pos, followPos) = getFramePositionsPropagating(txn, followFrame)

    _versions(followPos).pending += 1

    val result = notify0(pos, txn, changed)
    assertOptimizationsIntegrity(s"notifyFollowFrame($txn, $changed, $followFrame) -> $result")
    result
  }

  private def notify0(position: Int, txn: T, changed: Boolean): NotificationResultAction[T, OutDep] = {
    val version = _versions(position)
    // This assertion is probably pointless as it only verifies a subset of assertStabilityIsCorrect, i.e., if this
    // would fail, then assertStabilityIsCorrect will have failed at the end of the previous operation already.
    assert((position == firstFrame) == version.stable, "firstFrame and stable diverted..")

    // note: if the notification overtook a previous turn's notification with followFraming for this transaction,
    // pending may update from 0 to -1 here
    version.pending -= 1
    if (changed) {
      // note: if drop retrofitting overtook the change notification, change may update from -1 to 0 here!
      version.changed += 1
    }

    // check if the notification triggers subsequent actions
    if (version.pending == 0) {
      if (position == firstFrame) {
        if (version.changed > 0) {
          NotificationResultAction.GlitchFreeReady
        } else {
          // ResolvedFirstFrameToUnchanged
          progressToNextWriteForNotification(version)
        }
      } else {
        if (version.changed > 0) {
          NotificationResultAction.GlitchFreeReadyButQueued
        } else {
          NotificationResultAction.ResolvedNonFirstFrameToUnchanged
        }
      }
    } else {
      NotificationResultAction.NotGlitchFreeReady
    }
  }

  override def reevIn(turn: T): V = {
    synchronized {
      val firstFrameTurn = _versions(firstFrame).txn
      assert(firstFrameTurn == turn, s"Turn $turn called reevIn, but Turn $firstFrameTurn is first frame owner")
    }
    latestValue
  }

  /**
    * progress [[firstFrame]] forward until a [[Version.isFrame]] is encountered, and
    * return the resulting notification out (with reframing if subsequent write is found).
    */
  override def reevOut(turn: T, maybeValue: Option[V]): NotificationResultAction.NotificationOutAndSuccessorOperation[T, OutDep] = synchronized {
    val position = firstFrame
    val version = _versions(position)
    assert(version.txn == turn, s"$turn called reevDone, but first frame is $version (different transaction)")
    assert(!version.isWritten, s"$turn cannot write twice: $version")
    assert((version.isFrame && version.isReadyForReevaluation) || (maybeValue.isEmpty && version.isReadOrDynamic), s"$turn cannot write changed=${maybeValue.isDefined} on $version")

    latestReevOut = position
    if(maybeValue.isDefined) {
      latestWritten = position
      this.latestValue = maybeValue.get
      version.value = maybeValue
    }
    version.changed = 0

    val result = progressToNextWriteForNotification(version)
    assertOptimizationsIntegrity(s"reevOut($turn, ${maybeValue.isDefined}) -> $result")
    result
  }

  /**
    * progresses [[firstFrame]] forward until a [[Version.isFrame]] is encountered and assemble all necessary
    * information to send out change/nochange notifications for the given transaction. Also capture synchronized,
    * whether or not the possibly encountered write [[Version.isReadyForReevaluation]].
    * @return the notification and next reevaluation descriptor.
    */
  private def progressToNextWriteForNotification(finalizedVersion: Version): NotificationResultAction.NotificationOutAndSuccessorOperation[T, OutDep] = {
    val encounteredWaiters = stabilizeForwardsUntilFrame()
    val res = if(firstFrame < size) {
      val newFirstFrame = _versions(firstFrame)
      if(newFirstFrame.isReadyForReevaluation) {
        NotificationResultAction.NotificationOutAndSuccessorOperation.NextReevaluation(finalizedVersion.out, newFirstFrame.txn)
      } else {
        NotificationResultAction.NotificationOutAndSuccessorOperation.FollowFraming(finalizedVersion.out, newFirstFrame.txn)
      }
    } else {
      NotificationResultAction.NotificationOutAndSuccessorOperation.NoSuccessor(finalizedVersion.out)
    }
    if(encounteredWaiters) notifyAll()
    res
  }

  // =================== READ OPERATIONS ====================

  /**
    * ensures at least a read version is stored to track executed reads or dynamic operations.
    * @param txn the executing transaction
    * @return the version's position.
    */
  private def ensureReadVersion(txn: T, knownOrderedMinPos: Int = latestGChint + 1): (Int, Int) = {
    assert(knownOrderedMinPos > latestGChint, s"nonsensical minpos $knownOrderedMinPos <= latestGChint $latestGChint")
    if(knownOrderedMinPos == size) {
      assert(txn.isTransitivePredecessor(_versions(knownOrderedMinPos - 1).txn) || _versions(knownOrderedMinPos - 1).txn.phase == TurnPhase.Completed, s"illegal $knownOrderedMinPos: predecessor ${_versions(knownOrderedMinPos - 1).txn} not ordered in $this")
      val gcd = arrangeVersionArray(size)
      val pos = size
      createVersionInHole(pos, txn)
      (pos, gcd)
    } else if (_versions(latestReevOut).txn == txn) {
      (latestReevOut, 0)
    } else if (_versions(latestWritten).txn == txn) {
      (latestWritten, 0)
    } else {
      val (insertOrFound, _) = findOrPigeonHolePropagatingPredictive(txn, knownOrderedMinPos, fromFinalPredecessorRelationIsRecorded = true, knownOrderedMinPos, size, size, toFinalRelationIsRecorded = true, UnlockedUnknown)
      if(insertOrFound < 0) {
        val gcd = arrangeVersionArray(-insertOrFound)
        val pos = -insertOrFound - gcd
        createVersionInHole(pos, txn)
        (pos, gcd)
      } else {
        (insertOrFound, 0)
      }
    }
//    findOrPigeonHole(txn, knownOrderedMinPos, size, versionRequired = true)
  }

  /**
    * entry point for before(this); may suspend.
    *
    * @param txn the executing transaction
    * @return the corresponding [[Version.value]] from before this transaction, i.e., ignoring the transaction's
    *         own writes.
    */
  override def dynamicBefore(txn: T): V = synchronized {
    assert(!valuePersistency.isTransient, s"$txn invoked dynamicBefore on transient node")
    synchronized {
      val position = ensureReadVersion(txn)._1
      assertOptimizationsIntegrity(s"ensureReadVersion($txn)")
      val version = _versions(position)
      if(version.stable) {
        Left(beforeKnownStable(txn, position))
      } else {
        Right(version)
      }
    } match {
      case Left(value) => value
      case Right(version) =>

        ForkJoinPool.managedBlock(version.blockForStable)

        synchronized {
          val stablePosition = if(version.isFrame) firstFrame else findFinalPosition(txn, versionRequired = true)._1
          assert(stablePosition >= 0, "somehow, the version allocated above disappeared..")
          beforeKnownStable(txn, stablePosition)
        }
    }
  }

  override def staticBefore(txn: T): V = synchronized {
    beforeKnownStable(txn, math.abs(findFinalPosition(txn, versionRequired = false)._1))
  }

  private def beforeKnownStable(txn: T, position: Int): V = synchronized {
    assert(!valuePersistency.isTransient, "before read on transient node")
    assert(position > 0, s"$txn cannot read before first version")
    val lastWrite = if(latestWritten < position) {
      assert(!_versions.slice(latestWritten + 1, position).exists(_.isFrame), s"there is a frame between latestWritten and position $position of stable $txn in $this")
      _versions(latestWritten)
    } else {
      lastWriteUpTo(position - 1)
    }
    lastWrite.value.get
  }

  @tailrec private def lastWriteUpTo(pos: Int): Version = {
    assert(pos >= 0, s"could not find a previous written version, although at least the first version should always be readable")
    val version = _versions(pos)
    assert(!version.isFrame, s"found frame while searching for predecessor version to read -- forgotten dynamic access synchronization?")
    if(version.value.isDefined) {
      version
    } else {
      lastWriteUpTo(pos - 1)
    }
  }

  private def beforeOrInitKnownFinal(txn: T, position: Int): V = {
    if(valuePersistency.isTransient) {
      valuePersistency.initialValue
    } else {
      beforeKnownStable(txn, position)
    }
  }

  /**
    * entry point for after(this); may suspend.
    * @param txn the executing transaction
    * @return the corresponding [[Version.value]] from after this transaction, i.e., awaiting and returning the
    *         transaction's own write if one has occurred or will occur.
    */
  override def dynamicAfter(txn: T): V = {
    synchronized {
      val position = ensureReadVersion(txn)._1
      val version = _versions(position)
      if(version.isFinal) {
        Left(if(version.value.isDefined) {
          version.value.get
        } else {
          beforeOrInitKnownFinal(txn, position)
        })
      } else {
        Right(version)
      }
    } match {
      case Left(value) => value
      case Right(version) =>

        ForkJoinPool.managedBlock(version.blockForFinal)

        if(version.value.isDefined) {
          version.value.get
        } else {
          synchronized {
            beforeOrInitKnownFinal(txn, findFinalPosition(txn, versionRequired = true)._1)
          }
        }
    }
  }

  override def staticAfter(txn: T): V = synchronized {
    val position = findFinalPosition(txn, versionRequired = false)._1
    if(position < 0) {
      beforeOrInitKnownFinal(txn, -position)
    } else {
      val version = _versions(position)
      assert(!version.isFrame, s"staticAfter discovered frame $version -- did the caller wrongly assume a statically known dependency?")
      version.value.getOrElse {
        beforeOrInitKnownFinal(txn, position)
      }
    }
  }

  // =================== DYNAMIC OPERATIONS ====================

  /**
    * entry point for discover(this, add). May suspend.
    * @param txn the executing reevaluation's transaction
    * @param add the new edge's sink node
    * @return the appropriate [[Version.value]].
    */
  override def discover(txn: T, add: OutDep): (Seq[T], Option[T]) = synchronized {
    val position = ensureReadVersion(txn)._1
    assertOptimizationsIntegrity(s"ensureReadVersion($txn)")
    assert(!_versions(position).out.contains(add), "must not discover an already existing edge!")
    retrofitSourceOuts(position, add, +1)
  }

  /**
    * entry point for drop(this, ticket.issuer); may suspend temporarily.
    * @param txn the executing reevaluation's transaction
    * @param remove the removed edge's sink node
    */
  override def drop(txn: T, remove: OutDep): (Seq[T], Option[T]) = synchronized {
    val position = ensureReadVersion(txn)._1
    assertOptimizationsIntegrity(s"ensureReadVersion($txn)")
    assert(_versions(position).out.contains(remove), "must not drop a non-existing edge!")
    retrofitSourceOuts(position, remove, -1)
  }

  /**
    * performs the reframings on the sink of a discover(n, this) with arity +1, or drop(n, this) with arity -1
    * @param successorWrittenVersions the reframings to perform for successor written versions
    * @param maybeSuccessorFrame maybe a reframing to perform for the first successor frame
    * @param arity +1 for discover adding frames, -1 for drop removing frames.
    */
  override def retrofitSinkFrames(successorWrittenVersions: Seq[T], maybeSuccessorFrame: Option[T], arity: Int): Unit = synchronized {
    require(math.abs(arity) == 1)
    var minPos = firstFrame
    for(txn <- successorWrittenVersions) {
      val position = ensureReadVersion(txn, minPos)._1
      val version = _versions(position)
      // note: if drop retrofitting overtook a change notification, changed may update from 0 to -1 here!
      version.changed += arity
      minPos = position + 1
    }

    if (maybeSuccessorFrame.isDefined) {
      val txn = maybeSuccessorFrame.get
      val position = ensureReadVersion(txn, minPos)._1
      val version = _versions(position)
      // note: conversely, if a (no)change notification overtook discovery retrofitting, pending may change
      // from -1 to 0 here. No handling is required for this case, because firstFrame < position is an active
      // reevaluation (the one that's executing the discovery) and will afterwards progressToNextWrite, thereby
      // executing this then-ready reevaluation, but for now the version is guaranteed not stable yet.
      version.pending += arity
    }
  }

  /**
    * rewrites all affected [[Version.out]] of the source this during drop(this, delta) with arity -1 or
    * discover(this, delta) with arity +1, and collects transactions for retrofitting frames on the sink node
    * @param position the executing transaction's version's position
    * @param delta the outgoing dependency to add/remove
    * @param arity +1 to add, -1 to remove delta to each [[Version.out]]
    * @return a list of transactions with written successor versions and maybe the transaction of the first successor
    *         frame if it exists, for which reframings have to be performed at the sink.
    */
  private def retrofitSourceOuts(position: Int, delta: OutDep, arity: Int): (Seq[T], Option[T]) = {
    require(math.abs(arity) == 1)
    // allocate array to the maximum number of written versions that might follow
    // (any version at index firstFrame or later can only be a frame, not written)
    val sizePrediction = math.max(firstFrame - position, 0)
    val successorWrittenVersions = new ArrayBuffer[T](sizePrediction)
    for(pos <- position until size) {
      val version = _versions(pos)
      if(arity < 0) version.out -= delta else version.out += delta
      // as per above, this is implied false if pos >= firstFrame:
      if(version.isWritten) successorWrittenVersions += version.txn
    }
    if(successorWrittenVersions.size > sizePrediction) System.err.println(s"FullMV retrofitSourceOuts predicted size max($firstFrame - $position, 0) = $sizePrediction, but size eventually was ${successorWrittenVersions.size}")
    val maybeSuccessorFrame = if (firstFrame < size) Some(_versions(firstFrame).txn) else None
    (successorWrittenVersions, maybeSuccessorFrame)
  }

  def fullGC(): Int = synchronized {
    moveGCHintToLatestCompleted()
    arrangeVersionArray()
  }

  private def moveGCHintToLatestCompleted(): Unit = {
    @tailrec @inline def findLastCompleted(to: Int): Unit = {
      // gc = 0 = completed
      // to = 1 = !completed
      if (to > latestGChint) {
        val idx = latestGChint + (to - latestGChint + 1) / 2
        // 0 + (1 - 0 + 1) / 2 = 1
        if (_versions(idx).txn.phase == TurnPhase.Completed) {
            latestGChint = idx
            findLastCompleted(to)
        } else {
          findLastCompleted(idx - 1)
        }
      }
    }

    if (_versions(firstFrame - 1).txn.phase == TurnPhase.Completed) {
      // common case shortcut and corner case: all transactions that can be completed are completed (e.g., graph is in resting state)
      latestGChint = firstFrame - 1
    } else {
      findLastCompleted(firstFrame - 2)
    }
  }

  def arrangeVersionArray(firstHole: Int = -1, secondHole: Int = -1): Int = {
    assert(firstHole >= 0 || secondHole < 0, "must not give only a second hole")
    assert(secondHole < 0 || secondHole >= firstHole, "second hole must be behind or at first")
    val create = (if(firstHole >= 0) 1 else 0) + (if(secondHole >= 0) 1 else 0)
    if(firstHole == size && size + create <= _versions.length) {
      // if only versions should be added at the end (i.e., existing versions don't need to be moved) and there's enough room, just don't do anything
      0
    } else {
      if (NodeVersionHistory.DEBUG_GC) println(s"[${Thread.currentThread().getName}] gc attempt to insert $create: ($firstHole, $secondHole) in $this")
      val hintVersionIsWritten = _versions(latestGChint).value.isDefined
      val straightDump = latestGChint - (if (hintVersionIsWritten) 0 else 1)
      val res = if(straightDump == 0 && size + create <= _versions.length) {
        if (NodeVersionHistory.DEBUG_GC) println(s"[${Thread.currentThread().getName}] hintgc($latestGChint): -$straightDump would have no effect, but history rearrangement is possible")
        arrangeHolesWithoutGC(_versions, firstHole, secondHole)
        0
      } else if (size - straightDump + create <= _versions.length) {
        if(NodeVersionHistory.DEBUG_GC) println(s"[${Thread.currentThread().getName}] hintgc($latestGChint): -$straightDump accepted")
        gcAndLeaveHoles(_versions, hintVersionIsWritten, create, firstHole, secondHole)
      } else {
        // straight dump with gc hint isn't enough: see what full GC brings
        if(NodeVersionHistory.DEBUG_GC) println(s"[${Thread.currentThread().getName}] hintgc($latestGChint): -$straightDump insufficient and not enough room for history rearrangement")
        moveGCHintToLatestCompleted()
        val fullGCVersionIsWritten = _versions(latestGChint).value.isDefined
        val fullDump = latestGChint - (if (fullGCVersionIsWritten) 0 else 1)
        if (size - fullDump + create <= _versions.length) {
          if(NodeVersionHistory.DEBUG_GC) println(s"[${Thread.currentThread().getName}] fullgc($latestGChint): -$fullDump accepted")
          gcAndLeaveHoles(_versions, fullGCVersionIsWritten, create, firstHole, secondHole)
        } else {
          // full GC also isn't enough either: grow the array.
          val grown = new Array[Version](_versions.length + (_versions.length >> 1))
          val gcd = if(fullDump == 0) {
            if(NodeVersionHistory.DEBUG_GC) println(s"[${Thread.currentThread().getName}] fullgc($latestGChint): -$fullDump would have no effect, rearraging after growing max size ${_versions.length} -> ${grown.length}")
            if(firstHole > 0) System.arraycopy(_versions, 0, grown, 0, firstHole)
            arrangeHolesWithoutGC(grown, firstHole, secondHole)
            0
          } else {
            if(NodeVersionHistory.DEBUG_GC) println(s"[${Thread.currentThread().getName}] fullgc($latestGChint): -$fullDump insufficient, also growing max size ${_versions.length} -> ${grown.length}")
            gcAndLeaveHoles(grown, fullGCVersionIsWritten, create, firstHole, secondHole)
          }
          _versions = grown
          gcd
        }
      }
      if(NodeVersionHistory.DEBUG_GC) println(s"[${Thread.currentThread().getName}] after gc, holes at (${if(firstHole == -1) -1 else firstHole - res}, ${if(secondHole == -1) -1 else secondHole - res + 1}): $this")
      res
    }
  }

  private def arrangeHolesWithoutGC(writeTo: Array[Version], firstHole: Int, secondHole: Int): Unit = {
    if (firstHole >= 0 && firstHole < size) {
      if (secondHole < 0 || secondHole == size) {
        System.arraycopy(_versions, firstHole, writeTo, firstHole + 1, size - firstHole)
      } else {
        if (secondHole == firstHole) {
          System.arraycopy(_versions, firstHole, writeTo, firstHole + 2, size - firstHole)
        } else {
          System.arraycopy(_versions, secondHole, writeTo, secondHole + 2, size - secondHole)
          System.arraycopy(_versions, firstHole, writeTo, firstHole + 1, secondHole - firstHole)
        }
      }
    }
  }

  private def gcAndLeaveHoles(writeTo: Array[Version], hintVersionIsWritten: Boolean, create: Int, firstHole: Int, secondHole: Int) = {
    // if a straight dump using the gc hint makes enough room, just do that
    val dumpCount = if (hintVersionIsWritten) {
      if(NodeVersionHistory.DEBUG_GC) println(s"[${Thread.currentThread().getName}] hint is written: dumping $latestGChint to offset 0")
      // if hint is written, just dump everything before
      latestWritten -= latestGChint
      latestReevOut -= latestGChint
      dumpToOffsetAndLeaveHoles(writeTo, latestGChint, 0, firstHole, secondHole)
      latestGChint
    } else {
      // otherwise find the latest write before the hint, move it to index 0, and only dump everything else
      val dumpCount = latestGChint - 1
      writeTo(0) = if (latestWritten <= latestGChint) {
        if(NodeVersionHistory.DEBUG_GC) println(s"[${Thread.currentThread().getName}] hint is not written: preserving last write $latestWritten and dumping $dumpCount to offset 1")
        val result = _versions(latestWritten)
        latestWritten = 0
        latestReevOut = if(latestReevOut <= latestGChint) 0 else latestGChint - dumpCount
        result
      } else {
        val result = lastWriteUpTo(dumpCount)
        if(NodeVersionHistory.DEBUG_GC) println(s"[${Thread.currentThread().getName}] hint is not written: preserving last write by ${result.txn.hashCode()} and dumping $dumpCount to offset 1")
        latestWritten -= dumpCount
        latestReevOut -= dumpCount
        result
      }
      dumpToOffsetAndLeaveHoles(writeTo, latestGChint, 1, firstHole, secondHole)
      dumpCount
    }
    val sizeBefore = size
    latestGChint -= dumpCount
    firstFrame -= dumpCount
    size -= dumpCount
    if ((_versions eq writeTo) && size + create < sizeBefore) java.util.Arrays.fill(_versions.asInstanceOf[Array[AnyRef]], size + create, sizeBefore, null)
    dumpCount
  }

  private def dumpToOffsetAndLeaveHoles(writeTo: Array[Version], retainFrom: Int, retainTo: Int, firstHole: Int, secondHole: Int): Unit = {
    assert(retainFrom > retainTo, s"this method is either broken or pointless (depending on the number of inserts) if not at least one version is removed.")
    assert(firstHole >= 0 || secondHole < 0, "must not give only a second hole")
    assert(secondHole < 0 || secondHole >= firstHole, "second hole must be behind or at first")
    // just dump everything before the hint
    if (firstHole < 0 || firstHole == size) {
      // no hole or holes at the end only: the entire array stays in one segment
      System.arraycopy(_versions, retainFrom, writeTo, retainTo, size - retainFrom)
    } else {
      // copy first segment
      System.arraycopy(_versions, retainFrom, writeTo, retainTo, firstHole - retainFrom)
      val gcOffset = retainTo - retainFrom
      val newFirstHole = gcOffset + firstHole
      if (secondHole < 0 || secondHole == size) {
        // no second hole or second hole at the end only: there are only two segments
        if((_versions ne writeTo) || gcOffset != 1) System.arraycopy(_versions, firstHole, writeTo, newFirstHole + 1, size - firstHole)
      } else if (secondHole == firstHole) {
        // second hole same as first one: there are still only two segments, but leave two-wide hole inbetween
        if((_versions ne writeTo) || gcOffset != 2) System.arraycopy(_versions, firstHole, writeTo, newFirstHole + 2, size - firstHole)
      } else {
        // three segments with one hole between each
        if((_versions ne writeTo) || gcOffset != 1) System.arraycopy(_versions, firstHole, writeTo, newFirstHole + 1, secondHole - firstHole)
        if((_versions ne writeTo) || gcOffset != 2) System.arraycopy(_versions, secondHole, writeTo, gcOffset + secondHole + 2, size - secondHole)
      }
    }
  }
}

object NodeVersionHistory {
  val DEBUG_GC = false

  sealed trait TryOrderResult
  case object FailedNonfinal extends TryOrderResult
  sealed trait TryRecordResult extends TryOrderResult
  case object Succeeded extends TryRecordResult
  case object FailedFinalAndRecorded extends TryRecordResult
}
