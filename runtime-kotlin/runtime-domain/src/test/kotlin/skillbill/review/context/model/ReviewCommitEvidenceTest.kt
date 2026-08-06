package skillbill.review.context.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Commit-evidence identity, packet validation, bundle composition, and launch projection. */
class ReviewCommitEvidenceTest {
  private val hunkA = ReviewChangedHunk("src/A.kt", 1, 1, 1, 2, "+alpha")
  private val hunkB = ReviewChangedHunk("src/B.kt", 4, 1, 4, 1, "+beta")

  private fun unit(
    sha: String,
    parent: String,
    order: Int,
    hunks: List<ReviewChangedHunk>,
    subject: String = "commit $sha",
  ) = ReviewCommitUnit(sha, parent, subject, order, hunks, ReviewCommitSource.COMMIT_RANGE)

  private fun lane(name: String, paths: List<String>) = ReviewLaneDecision(
    name,
    true,
    "routed",
    ownedPaths = paths,
    originLayerChains = listOf(listOf("kotlin")),
    owningPack = "kotlin",
    specialistSkillName = "bill-kotlin-code-review-$name",
  )

  private fun packet(
    units: List<ReviewCommitUnit>,
    hunks: List<ReviewChangedHunk> = units.flatMap { it.hunks },
    coverage: ReviewCommitCoverageFact = ReviewCommitCoverageFact(
      "base",
      "head",
      units.size,
      chainVerified = true,
      pathCoverageVerified = true,
    ),
    paths: List<String> = hunks.map { it.path }.distinct().sorted(),
  ) = ReviewContextPacket(
    reviewId = "review",
    repositoryIdentity = "repo",
    baseRevision = "base",
    headRevision = "head",
    status = "clean",
    stack = "kotlin",
    pack = "kotlin",
    addOns = emptyList(),
    selectedLanes = listOf("security"),
    changedHunks = hunks,
    commitUnits = units,
    coverageFact = coverage,
    reviewRevision = ReviewRevision("rvs", 1),
    laneDecisions = listOf(lane("security", paths)),
  )

  private val twoCommits = listOf(
    unit("c1", "base", 0, listOf(hunkA)),
    unit("head", "c1", 1, listOf(hunkB)),
  )

  // AC-002
  @Test fun `commit unit id tracks identity order parent subject and hunk content`() {
    val base = unit("c1", "base", 0, listOf(hunkA))
    assertEquals(base.commitUnitId, unit("c1", "base", 0, listOf(hunkA)).commitUnitId)
    assertNotEquals(base.commitUnitId, base.copy(commitSha = "c2").commitUnitId)
    assertNotEquals(base.commitUnitId, base.copy(parentSha = "other").commitUnitId)
    assertNotEquals(base.commitUnitId, base.copy(subject = "different").commitUnitId)
    assertNotEquals(base.commitUnitId, base.copy(orderIndex = 1).commitUnitId)
    assertNotEquals(base.commitUnitId, base.copy(hunks = listOf(hunkB)).commitUnitId)
  }

  // AC-001, AC-002: hunk identity is commit-owned, so identical content in two commits stays distinct.
  @Test fun `ofCommit scopes hunk identity to its owning commit`() {
    val first = ReviewCommitUnit.ofCommit("c1", "base", "first", 0, listOf(hunkA))
    val second = ReviewCommitUnit.ofCommit("head", "c1", "second", 1, listOf(hunkA))
    assertNotEquals(first.hunkIds.single(), second.hunkIds.single())
    assertEquals(
      first.hunkIds,
      ReviewCommitUnit.ofCommit("c1", "base", "first", 0, listOf(hunkA)).hunkIds,
    )
    assertEquals(ReviewCommitUnit.commitScopeKey("c1", 0), first.hunks.single().commitScope)
    // A hunk repeated inside one commit still collides, so a commit cannot own it twice.
    assertFailsWith<IllegalArgumentException> {
      ReviewCommitUnit.ofCommit("c1", "base", "first", 0, listOf(hunkA, hunkA))
    }
  }

  // AC-005
  @Test fun `synthetic units declare their source and never fabricate a commit sha`() {
    val synthetic = ReviewCommitUnit.synthetic(ReviewCommitSource.SYNTHETIC_SUPPLIED_DIFF, listOf(hunkA))
    assertTrue(synthetic.commitSha.startsWith(REVIEW_SYNTHETIC_COMMIT_PREFIX))
    assertTrue(synthetic.parentSha.startsWith(REVIEW_SYNTHETIC_COMMIT_PREFIX))
    assertEquals(ReviewCommitSource.SYNTHETIC_SUPPLIED_DIFF, synthetic.source)
    assertFailsWith<IllegalArgumentException> {
      ReviewCommitUnit("abc123", "base", "s", 0, listOf(hunkA), ReviewCommitSource.SYNTHETIC_WORKING_TREE)
    }
    assertFailsWith<IllegalArgumentException> {
      ReviewCommitUnit.synthetic(ReviewCommitSource.COMMIT_RANGE, listOf(hunkA))
    }
  }

  // AC-005
  @Test fun `a synthetic unit is the only unit its packet may carry`() {
    val synthetic = packet(
      listOf(ReviewCommitUnit.synthetic(ReviewCommitSource.SYNTHETIC_WORKING_TREE, listOf(hunkA, hunkB))),
      coverage = ReviewCommitCoverageFact(
        "base",
        "head",
        1,
        chainVerified = false,
        pathCoverageVerified = true,
        degradedReason = "working-tree scope",
      ),
    )
    assertEquals(1, synthetic.commitUnits.size)
    assertFailsWith<IllegalArgumentException> {
      packet(
        listOf(
          ReviewCommitUnit.synthetic(ReviewCommitSource.SYNTHETIC_WORKING_TREE, listOf(hunkA)),
          unit("head", "base", 1, listOf(hunkB)),
        ),
      )
    }
  }

  // AC-003
  @Test fun `packet rejects every malformed commit and hunk reference class`() {
    val orphan = ReviewChangedHunk("src/C.kt", 9, 1, 9, 1, "+gamma")
    // A hunk with no owning commit unit.
    assertFailsWith<IllegalArgumentException> {
      packet(twoCommits, hunks = listOf(hunkA, hunkB, orphan), paths = listOf("src/A.kt", "src/B.kt", "src/C.kt"))
    }
    // A commit unit naming a hunk the packet does not carry.
    assertFailsWith<IllegalArgumentException> {
      packet(twoCommits, hunks = listOf(hunkA))
    }
    // The same hunk claimed by two commit units.
    assertFailsWith<IllegalArgumentException> {
      packet(listOf(unit("c1", "base", 0, listOf(hunkA)), unit("head", "c1", 1, listOf(hunkA))))
    }
    // A duplicate commit identity.
    assertFailsWith<IllegalArgumentException> {
      packet(listOf(unit("head", "base", 0, listOf(hunkA)), unit("head", "head", 1, listOf(hunkB))))
    }
    // Out-of-order (non-contiguous) order indices.
    assertFailsWith<IllegalArgumentException> {
      packet(listOf(unit("c1", "base", 0, listOf(hunkA)), unit("head", "c1", 2, listOf(hunkB))))
    }
    // A broken parent chain.
    assertFailsWith<IllegalArgumentException> {
      packet(listOf(unit("c1", "base", 0, listOf(hunkA)), unit("head", "unrelated", 1, listOf(hunkB))))
    }
    // Endpoints that do not span base..head.
    assertFailsWith<IllegalArgumentException> {
      packet(listOf(unit("c1", "elsewhere", 0, listOf(hunkA)), unit("head", "c1", 1, listOf(hunkB))))
    }
    assertFailsWith<IllegalArgumentException> {
      packet(listOf(unit("c1", "base", 0, listOf(hunkA)), unit("c2", "c1", 1, listOf(hunkB))))
    }
    // A packet with no commit sequence at all.
    assertFailsWith<IllegalArgumentException> { packet(emptyList(), hunks = listOf(hunkA)) }
  }

  // AC-004
  @Test fun `two commits touching the same file still partition the packet`() {
    val firstEdit = ReviewChangedHunk("src/A.kt", 10, 1, 10, 1, "+first")
    val secondEdit = ReviewChangedHunk("src/A.kt", 10, 1, 10, 1, "+second")
    val built = packet(
      listOf(unit("c1", "base", 0, listOf(firstEdit)), unit("head", "c1", 1, listOf(secondEdit))),
    )
    assertEquals(setOf(firstEdit.hunkId, secondEdit.hunkId), built.ownedHunkIds)
    assertEquals(setOf("c1", "head"), built.ownedCommitIds)
  }

  // AC-004
  @Test fun `an unverified coverage fact must name its reason`() {
    assertFailsWith<IllegalArgumentException> {
      ReviewCommitCoverageFact("base", "head", 1, chainVerified = false, pathCoverageVerified = true)
    }
    assertFailsWith<IllegalArgumentException> {
      ReviewCommitCoverageFact("base", "head", 0, chainVerified = true, pathCoverageVerified = true)
    }
  }

  // AC-002
  @Test fun `packet digest tracks commit order parent and coverage status`() {
    val built = packet(twoCommits)
    assertEquals(built.digest, packet(twoCommits).digest)
    val reordered = packet(
      listOf(unit("c1", "base", 1, listOf(hunkA)), unit("head", "c1", 0, listOf(hunkB))),
    )
    assertNotEquals(built.digest, reordered.digest)
    assertNotEquals(
      built.digest,
      packet(
        listOf(unit("c1", "base", 0, listOf(hunkA)), unit("head", "c1", 1, listOf(hunkB), "other subject")),
      ).digest,
    )
    assertNotEquals(
      built.digest,
      built.copy(
        coverageFact = ReviewCommitCoverageFact(
          "base",
          "head",
          2,
          chainVerified = false,
          pathCoverageVerified = true,
          degradedReason = "unverified",
        ),
      ).digest,
    )
  }

  private fun assignment(built: ReviewContextPacket, bundle: ReviewLaneBundle) = ReviewAssignment(
    reviewId = built.reviewId,
    packetDigest = built.digest,
    lane = "security",
    baseRevision = built.baseRevision,
    headRevision = built.headRevision,
    assignedPaths = listOf("src/A.kt", "src/B.kt"),
    assignedHunks = listOf(hunkA.hunkId, hunkB.hunkId),
    assignedBundle = bundle,
    reviewRevision = built.reviewRevision,
    laneDecision = built.laneDecisions.single(),
  )

  private val fullBundle = ReviewLaneBundle(
    listOf(
      ReviewLaneBundleEntry("c1", 0, listOf(hunkA.hunkId)),
      ReviewLaneBundleEntry("head", 1, listOf(hunkB.hunkId)),
    ),
  )

  // AC-008
  @Test fun `bundle composition and entry order change the assignment digest`() {
    val built = packet(twoCommits)
    val base = assignment(built, fullBundle)
    assertNotEquals(
      base.digest,
      base.copy(
        assignedBundle = ReviewLaneBundle(
          listOf(ReviewLaneBundleEntry("c1", 0, listOf(hunkA.hunkId, hunkB.hunkId))),
        ),
      ).digest,
    )
    assertEquals(base.digest, assignment(built, fullBundle).digest)
  }

  // AC-008
  @Test fun `bundles reject stray hunks and out-of-order entries`() {
    assertFailsWith<IllegalArgumentException> {
      ReviewLaneBundle(
        listOf(
          ReviewLaneBundleEntry("head", 1, listOf(hunkB.hunkId)),
          ReviewLaneBundleEntry("c1", 0, listOf(hunkA.hunkId)),
        ),
      )
    }
    assertFailsWith<IllegalArgumentException> {
      ReviewLaneBundle(
        listOf(
          ReviewLaneBundleEntry("c1", 0, listOf(hunkA.hunkId)),
          ReviewLaneBundleEntry("head", 1, listOf(hunkA.hunkId)),
        ),
      )
    }
    // A bundle hunk that is not assigned to the lane.
    assertFailsWith<IllegalArgumentException> {
      assignment(packet(twoCommits), fullBundle).copy(assignedHunks = listOf(hunkA.hunkId))
    }
  }

  private fun launch(built: ReviewContextPacket, bundle: ReviewLaneBundle) = GovernedReviewLaunch(
    assignment(built, bundle),
    built,
    "contract",
    "rubric",
    "broker",
    ReviewContextBudgetPolicy.DEFAULT,
  )

  // AC-006, AC-008
  @Test fun `launch projects every hunk body under exactly one commit and no aggregate diff`() {
    val payload = launch(packet(twoCommits), fullBundle).canonicalPayload
    assertTrue("commit_sha: \"c1\"" in payload)
    assertTrue("commit_sha: \"head\"" in payload)
    assertEquals(1, Regex(Regex.escape(hunkA.content)).findAll(payload).count())
    assertEquals(1, Regex(Regex.escape(hunkB.content)).findAll(payload).count())
    assertTrue("complete_diff" !in payload)
    assertTrue("coverage_fact:" in payload)
  }

  // AC-003, AC-008
  @Test fun `launch rejects a bundle naming a commit outside the packet`() {
    val built = packet(twoCommits)
    val foreign = ReviewLaneBundle(
      listOf(
        ReviewLaneBundleEntry("not-in-packet", 0, listOf(hunkA.hunkId)),
        ReviewLaneBundleEntry("head", 1, listOf(hunkB.hunkId)),
      ),
    )
    assertFailsWith<IllegalArgumentException> { launch(built, foreign) }
    val misordered = ReviewLaneBundle(
      listOf(
        ReviewLaneBundleEntry("c1", 0, listOf(hunkA.hunkId)),
        ReviewLaneBundleEntry("head", 5, listOf(hunkB.hunkId)),
      ),
    )
    assertFailsWith<IllegalArgumentException> { launch(built, misordered) }
    val incomplete = ReviewLaneBundle(listOf(ReviewLaneBundleEntry("c1", 0, listOf(hunkA.hunkId))))
    assertFailsWith<IllegalArgumentException> { launch(built, incomplete) }
    val misattributed = ReviewLaneBundle(
      listOf(ReviewLaneBundleEntry("c1", 0, listOf(hunkA.hunkId, hunkB.hunkId))),
    )
    assertFailsWith<IllegalArgumentException> { launch(built, misattributed) }
  }
}
