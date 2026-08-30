package skillbill.review.context.model

internal object ReviewContextPacketCommitEvidenceValidator {
  fun validate(packet: ReviewContextPacket, ownedHunks: Set<String>) {
    validateCommitUnitsPresent(packet.commitUnits)
    validateCommitUnitIdentity(packet.commitUnits)
    validateCommitUnitOrder(packet.commitUnits)
    validateCoverageFactCount(packet.coverageFact, packet.commitUnits)
    validateCommitChain(packet)
    validateHunkPartition(packet.commitUnits, ownedHunks)
  }

  private fun validateCommitUnitsPresent(commitUnits: List<ReviewCommitUnit>) {
    require(commitUnits.isNotEmpty()) {
      "A review packet is missing its commit sequence; at least one unit is required."
    }
  }

  private fun validateCommitUnitIdentity(commitUnits: List<ReviewCommitUnit>) {
    require(commitUnits.map { it.commitSha }.distinct().size == commitUnits.size) {
      "Review packet carries a duplicate commit identity."
    }
  }

  private fun validateCommitUnitOrder(commitUnits: List<ReviewCommitUnit>) {
    require(commitUnits.map { it.orderIndex }.sorted() == commitUnits.indices.toList()) {
      "Review packet commit units are out of order; order indices must form a contiguous 0..n-1 sequence."
    }
  }

  private fun validateCoverageFactCount(coverageFact: ReviewCommitCoverageFact, commitUnits: List<ReviewCommitUnit>) {
    require(coverageFact.commitCount == commitUnits.size) {
      "Coverage fact counts ${coverageFact.commitCount} commits but the packet carries ${commitUnits.size}."
    }
  }

  private fun validateCommitChain(packet: ReviewContextPacket) {
    val ordered = packet.commitUnits.sortedBy { it.orderIndex }
    if (ordered.any { it.source.isSynthetic }) {
      require(ordered.size == 1) { "A synthetic review unit must be the only unit in its packet." }
      return
    }
    require(ordered.first().parentSha == packet.baseRevision) {
      "Review packet commit chain does not start at the base revision '${packet.baseRevision}'."
    }
    require(ordered.last().commitSha == packet.headRevision) {
      "Review packet commit chain does not end at the head revision '${packet.headRevision}'."
    }
    ordered.zipWithNext().forEach { (previous, next) ->
      require(next.parentSha == previous.commitSha) {
        "Review packet commit chain is broken: '${next.commitSha}' does not descend from '${previous.commitSha}'."
      }
    }
  }

  private fun validateHunkPartition(commitUnits: List<ReviewCommitUnit>, ownedHunks: Set<String>) {
    val ordered = commitUnits.sortedBy { it.orderIndex }
    val unitHunkIds = ordered.flatMap { it.hunkIds }
    require(unitHunkIds.distinct().size == unitHunkIds.size) {
      "A changed hunk is claimed by more than one commit unit; commit units must partition the packet hunks."
    }
    val absent = unitHunkIds.filterNot { it in ownedHunks }
    require(absent.isEmpty()) { "A commit unit references a hunk absent from the packet changed hunks." }
    val unowned = ownedHunks - unitHunkIds.toSet()
    require(unowned.isEmpty()) { "Packet changed hunks are unowned by any commit unit: ${unowned.size} hunk(s)." }
  }
}
