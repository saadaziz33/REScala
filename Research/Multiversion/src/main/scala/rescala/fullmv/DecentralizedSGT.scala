package rescala.fullmv

object DecentralizedSGT extends SerializationGraphTracking[FullMVTurn] {
  override def getOrder(defender: FullMVTurn, contender: FullMVTurn): PartialOrderResult = {
    assert(defender != contender, s"$defender compared with itself..?")
    assert(defender.phase > TurnPhase.Initialized, s"$defender is not started and should thus not be involved in any operations")
    assert(contender.phase > TurnPhase.Initialized, s"$contender is not started and should thus not be involved in any operations")
    assert(contender.phase < TurnPhase.Completed, s"$contender cannot be a searcher (already completed).")
    if(defender.phase == TurnPhase.Completed) {
      FirstFirstSCCUnkown
    } else if (contender.isTransitivePredecessor(defender)) {
      FirstFirstSameSCC
    } else if (defender.isTransitivePredecessor(contender)) {
      SecondFirstSameSCC
    } else {
      UnorderedSCCUnknown
    }
  }

  override def ensureOrder(defender: FullMVTurn, contender: FullMVTurn): OrderResult = {
    assert(defender != contender, s"cannot establish order between equal defender $defender and contender $contender")
    assert(defender.phase > TurnPhase.Initialized, s"$defender is not started and should thus not be involved in any operations")
    assert(contender.phase > TurnPhase.Initialized, s"$contender is not started and should thus not be involved in any operations")
    assert(contender.phase < TurnPhase.Completed, s"$contender cannot be a contender (already completed).")
    if(defender.phase == TurnPhase.Completed) {
      FirstFirst
    } else{
      if(contender.isTransitivePredecessor(defender)) {
        FirstFirst
      } else if (defender.isTransitivePredecessor(contender)) {
        SecondFirst
      } else {
        // unordered nested acquisition of two monitors here is safe against deadlocks because the turns' locks
        // (see assertions) ensure that only a single thread at a time will ever attempt to do so.

        val (contenderPhase, contenderPredecessorsSpanningTree) = contender.acquirePhaseLockAndGetEstablishmentBundle()
        val (defenderPhase, defenderPredecessorsSpanningTree) = defender.acquirePhaseLockAndGetEstablishmentBundle()
        if (defenderPhase < contenderPhase) {
          defender.addPredecessorAndReleasePhaseLock(contenderPredecessorsSpanningTree)
          contender.asyncReleasePhaseLock()
          SecondFirst
        } else {
          contender.addPredecessorAndReleasePhaseLock(defenderPredecessorsSpanningTree)
          defender.asyncReleasePhaseLock()
          FirstFirst
        }
      }
    }
  }
}
