package skillbill.workflow.taskruntime.model

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_AUDIT_REPAIR_CYCLE_CONTRACT_VERSION
import skillbill.error.AuditRepairCycleConflictError
import skillbill.error.InvalidAuditRepairCycleSchemaError
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AuditRepairCycleTest {
  @Test
  fun `multi-gap cycle retains diagnosis and repair outcomes while publishing only final assessment`() {
    val diagnosed = cycle()
    assertNull(diagnosed.finalAssessment)
    val ready = readyForFinalAudit(diagnosed)
    assertNull(ready.finalAssessment)
    val final = assessment(after, satisfied = true)
    val satisfied = ready.append(
      ready.current.revision,
      revision(4, AuditRepairStage.SATISFIED).copy(assessment = final),
    )
    val reopened = AuditRepairCycleCodec.decode(AuditRepairCycleCodec.encode(satisfied))
    assertEquals(diagnosed.diagnosis, reopened.diagnosis)
    assertEquals(setOf("AC-001", "AC-002"), reopened.diagnosis.unmetCriterionRefs)
    assertEquals(final, reopened.finalAssessment)
    assertEquals("Run the downstream validation gate.", reopened.finalAssessment?.criteria?.last()?.pendingValidation)
    assertEquals(1, reopened.repairRoundCount)
    assertEquals(listOf("src/One.kt", "src/Two.kt"), reopened.revisions[2].repairOutcomes?.flatMap { it.changedPaths })
  }

  @Test
  fun `post repair checkpoint cannot reuse the diagnosis identity for a different tree`() {
    val ready = readyForFinalAudit(cycle())
    val pending = ready.copy(revisions = ready.revisions.dropLast(1))
    val conflicting = ready.current.copy(checkpoint = after.copy(checkpointId = before.checkpointId))
    assertFailsWith<InvalidAuditRepairCycleSchemaError> { pending.append(2, conflicting) }
    val stored = AuditRepairCycleCodec.encode(ready)
      .replace(after.checkpointId, before.checkpointId)
    assertFailsWith<InvalidAuditRepairCycleSchemaError> { AuditRepairCycleCodec.decode(stored) }
    assertEquals(before, pending.diagnosis.checkpoint)
    assertNull(pending.finalAssessment)
  }

  @Test
  fun `repair receipts omitted criteria stale checkpoints and remaining gaps cannot satisfy final audit`() {
    val ready = readyForFinalAudit(cycle())
    val valid = assessment(after, satisfied = true)
    val invalid = listOf(
      revision(4, AuditRepairStage.SATISFIED),
      revision(4, AuditRepairStage.SATISFIED).copy(assessment = valid.copy(criteria = valid.criteria.dropLast(1))),
      revision(4, AuditRepairStage.SATISFIED).copy(assessment = valid.copy(checkpoint = before)),
      revision(4, AuditRepairStage.SATISFIED).copy(assessment = assessment(after, satisfied = false)),
    )
    invalid.forEach { proposed ->
      assertFailsWith<InvalidAuditRepairCycleSchemaError> { ready.append(ready.current.revision, proposed) }
      assertNull(ready.finalAssessment)
    }
  }

  @Test
  fun `final audits cannot replace or discard the pending validation obligation`() {
    val ready = readyForFinalAudit(cycle())
    listOf(true, false).forEach { satisfied ->
      listOf(null, "Validation already passed.").forEach { replacement ->
        val final = assessment(after, satisfied).let { assessment ->
          assessment.copy(
            criteria = assessment.criteria.map {
              if (it.pendingValidation == null) it else it.copy(pendingValidation = replacement)
            },
          )
        }
        val stage = if (satisfied) AuditRepairStage.SATISFIED else AuditRepairStage.PAUSED
        assertFailsWith<InvalidAuditRepairCycleSchemaError> {
          ready.append(
            3,
            revision(4, stage).copy(
              assessment = final,
              reason = if (satisfied) null else "Unresolved criteria require operator action.",
            ),
          )
        }
        assertEquals("Run the downstream validation gate.", ready.diagnosis.criteria.last().pendingValidation)
        assertNull(ready.finalAssessment)
      }
    }
  }

  @Test
  fun `final audit cannot disguise recurring gaps by replacing or swapping repair identities`() {
    val ready = readyForFinalAudit(cycle())
    val failed = assessment(after, satisfied = false)
    listOf(
      failed.criteria.map { it.copy(repairId = "replacement-${it.criterionRef}") },
      failed.criteria.mapIndexed { index, criterion -> criterion.copy(repairId = "repair-${2 - index}") },
    ).forEach { criteria ->
      assertFailsWith<InvalidAuditRepairCycleSchemaError> {
        ready.append(
          3,
          revision(4, AuditRepairStage.PAUSED).copy(
            assessment = failed.copy(criteria = criteria),
            reason = "Unresolved criteria require operator action.",
          ),
        )
      }
      assertEquals(setOf("repair-1", "repair-2"), ready.diagnosis.criteria.mapNotNull { it.repairId }.toSet())
      assertNull(ready.finalAssessment)
    }
  }

  @Test
  fun `new final audit gap cannot reuse the identity of a resolved diagnosis gap`() {
    val initial = cycle()
    val diagnosis = initial.diagnosis.copy(
      criteria = listOf(initial.diagnosis.criteria.first(), assessment(before, true).criteria.last()),
    )
    val ready = readyForFinalAudit(initial.copy(revisions = listOf(initial.current.copy(assessment = diagnosis))))
    val failed = assessment(after, false).copy(
      criteria = listOf(assessment(after, true).criteria.first(), assessment(after, false).criteria.last()),
    )
    val pause = revision(4, AuditRepairStage.PAUSED).copy(
      assessment = failed,
      reason = "A new gap requires operator action.",
    )
    val reusedIdentity = failed.copy(
      criteria = failed.criteria.map { if (it.satisfied) it else it.copy(repairId = "repair-1") },
    )
    assertFailsWith<InvalidAuditRepairCycleSchemaError> {
      ready.append(3, pause.copy(assessment = reusedIdentity))
    }
    val reopened = AuditRepairCycleCodec.decode(AuditRepairCycleCodec.encode(ready.append(3, pause)))
    assertEquals(setOf("AC-002"), reopened.current.assessment?.unmetCriterionRefs)
    assertEquals("repair-2", reopened.current.assessment?.criteria?.last()?.repairId)
    assertEquals(diagnosis, reopened.diagnosis)
    assertNull(reopened.finalAssessment)
  }

  @Test
  fun `failed final audit survives reopen as a pause with diagnosis and new gaps`() {
    val ready = readyForFinalAudit(cycle())
    val failed = assessment(after, satisfied = false)
    val paused = ready.append(
      ready.current.revision,
      revision(
        4,
        AuditRepairStage.PAUSED,
      ).copy(assessment = failed, reason = "Unresolved criteria require operator action."),
    )
    val reopened = AuditRepairCycleCodec.decode(AuditRepairCycleCodec.encode(paused))
    assertEquals(failed, reopened.current.assessment)
    assertEquals(before, reopened.diagnosis.checkpoint)
    assertNull(reopened.finalAssessment)
    val retried = reopened.append(
      4,
      revision(5, AuditRepairStage.AUTHORIZED_REPAIR).copy(repositoryFingerprint = after.repositoryFingerprint),
    )
    assertEquals(reopened.diagnosis, retried.diagnosis)
    assertEquals(2, retried.repairRoundCount)
  }

  @Test
  fun `exact replay preserves evidence and conflicting replay cannot replace diagnosis`() {
    val diagnosed = cycle()
    val authorized = revision(
      1,
      AuditRepairStage.AUTHORIZED_REPAIR,
    ).copy(repositoryFingerprint = before.repositoryFingerprint)
    val repaired = diagnosed.append(0, authorized)
    assertEquals(repaired, repaired.append(0, authorized))
    assertFailsWith<AuditRepairCycleConflictError> {
      repaired.append(0, authorized.copy(repositoryFingerprint = "different"))
    }
    assertFailsWith<AuditRepairCycleConflictError> {
      repaired.append(0, revision(2, AuditRepairStage.PAUSED).copy(reason = "stale revision"))
    }
    assertEquals(diagnosed.diagnosis, repaired.diagnosis)
    assertEquals(1, repaired.repairRoundCount)
  }

  @Test
  fun `changed diagnosis tree and unauthorized repair identity are rejected`() {
    val diagnosed = cycle()
    assertFailsWith<InvalidAuditRepairCycleSchemaError> {
      diagnosed.append(0, revision(1, AuditRepairStage.AUTHORIZED_REPAIR).copy(repositoryFingerprint = "changed"))
    }
    val authorized = diagnosed.append(
      0,
      revision(1, AuditRepairStage.AUTHORIZED_REPAIR).copy(repositoryFingerprint = before.repositoryFingerprint),
    )
    assertFailsWith<InvalidAuditRepairCycleSchemaError> {
      authorized.append(
        1,
        revision(2, AuditRepairStage.CHECKPOINT_PENDING).copy(
          checkpointIntent = "post-repair-intent",
          checkpoint = after,
          repositoryFingerprint = after.repositoryFingerprint,
          repairOutcomes = listOf(
            AuditRepairOutcome("unrelated-repair", "Changed unrelated code.", listOf("src/Other.kt")),
          ),
        ),
      )
    }
  }

  @Test
  fun `unchanged or unproven repair tree cannot reach final audit and retains diagnosis when paused`() {
    val authorized = cycle().append(
      0,
      revision(1, AuditRepairStage.AUTHORIZED_REPAIR).copy(repositoryFingerprint = before.repositoryFingerprint),
    )
    listOf(before.repositoryFingerprint, UNPROVEN_REPOSITORY_FINGERPRINT).forEach { fingerprint ->
      assertFailsWith<InvalidAuditRepairCycleSchemaError> {
        authorized.append(
          1,
          revision(2, AuditRepairStage.CHECKPOINT_PENDING).copy(
            checkpointIntent = "post-repair-intent",
            repositoryFingerprint = fingerprint,
            repairOutcomes = listOf(
              AuditRepairOutcome("repair-1", "Claimed first repair.", listOf("src/One.kt")),
              AuditRepairOutcome("repair-2", "Claimed second repair.", listOf("src/Two.kt")),
            ),
          ),
        )
      }
    }
    val paused = authorized.append(
      1,
      revision(2, AuditRepairStage.PAUSED).copy(reason = "Repository progress is unchanged or unproven."),
    )
    val reopened = AuditRepairCycleCodec.decode(AuditRepairCycleCodec.encode(paused))
    assertEquals(authorized.diagnosis, reopened.diagnosis)
    assertEquals(paused.current, reopened.current)
    assertEquals(1, reopened.repairRoundCount)
    assertNull(reopened.finalAssessment)
  }

  @Test
  fun `initially satisfied diagnosis still requires checkpoint and final audit without invented repairs`() {
    val initial = cycle(satisfied = true)
    val pending = initial.append(
      0,
      revision(1, AuditRepairStage.CHECKPOINT_PENDING).copy(
        checkpointIntent = "unchanged-tree-intent",
        checkpoint = before,
        repositoryFingerprint = before.repositoryFingerprint,
        repairOutcomes = emptyList(),
      ),
    )
    val ready = pending.append(1, revision(2, AuditRepairStage.FINAL_AUDIT).copy(checkpoint = before))
    val satisfied = ready.append(2, revision(3, AuditRepairStage.SATISFIED).copy(assessment = initial.diagnosis))
    assertEquals(0, satisfied.repairRoundCount)
    assertEquals(initial.diagnosis, satisfied.finalAssessment)
    assertEquals(emptyList(), satisfied.revisions[1].repairOutcomes)
  }

  @Test
  fun `malformed version stage and incomplete diagnosis fail through typed contract error`() {
    val initial = cycle()
    assertFailsWith<InvalidAuditRepairCycleSchemaError> { initial.copy(contractVersion = "unsupported").validate() }
    assertFailsWith<InvalidAuditRepairCycleSchemaError> { AuditRepairStage.fromWire("repair_complete") }
    assertFailsWith<InvalidAuditRepairCycleSchemaError> { AuditRepairCycleCodec.decode("[]") }
    val gapWithoutGuidance = initial.diagnosis.copy(
      criteria = initial.diagnosis.criteria.map { it.copy(repairGuidance = null) },
    )
    assertFailsWith<InvalidAuditRepairCycleSchemaError> {
      initial.copy(revisions = listOf(initial.current.copy(assessment = gapWithoutGuidance))).validate()
    }
  }

  @Test
  fun `cycle contract version and stage vocabulary match authored schema`() {
    val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
      .first { Files.isDirectory(it.resolve("orchestration")) }
    val schema = ObjectMapper(YAMLFactory()).readTree(
      root.resolve("orchestration/contracts/feature-task-runtime-audit-repair-cycle.schema.yaml").toFile(),
    )
    assertEquals(
      FEATURE_TASK_RUNTIME_AUDIT_REPAIR_CYCLE_CONTRACT_VERSION,
      schema.path("properties").path("contract_version").path("const").asText(),
    )
    assertEquals(
      AuditRepairStage.entries.map { it.wireValue }.toSet(),
      schema.path("\$defs").path("revision").path("properties").path("stage").path("enum").map { it.asText() }.toSet(),
    )
  }

  private fun readyForFinalAudit(initial: AuditRepairCycle): AuditRepairCycle {
    val authorized = initial.append(
      0,
      revision(1, AuditRepairStage.AUTHORIZED_REPAIR).copy(repositoryFingerprint = before.repositoryFingerprint),
    )
    val pending = authorized.append(
      1,
      revision(2, AuditRepairStage.CHECKPOINT_PENDING).copy(
        checkpointIntent = "post-repair-intent",
        checkpoint = after,
        repositoryFingerprint = after.repositoryFingerprint,
        repairOutcomes = listOf(
          AuditRepairOutcome("repair-1", "Corrected first gap.", listOf("src/One.kt")),
          AuditRepairOutcome("repair-2", "Corrected second gap.", listOf("src/Two.kt")),
        ).filter { outcome -> initial.diagnosis.criteria.any { it.repairId == outcome.repairId } },
      ),
    )
    return pending.append(2, revision(3, AuditRepairStage.FINAL_AUDIT).copy(checkpoint = after))
  }

  private fun cycle(satisfied: Boolean = false): AuditRepairCycle = AuditRepairCycle(
    identity = AuditRepairIdentity("workflow", 1, "execution", "session", "cycle", "owner", 1),
    criterionRefs = listOf("AC-001", "AC-002"),
    revisions = listOf(revision(0, AuditRepairStage.DIAGNOSIS).copy(assessment = assessment(before, satisfied))),
  ).also { it.validate() }

  private fun assessment(checkpoint: AuditRepairCheckpoint, satisfied: Boolean): AuditRepairAssessment =
    AuditRepairAssessment(
      checkpoint = checkpoint,
      criteria = (1..2).map { index ->
        AuditRepairCriterion(
          criterionRef = "AC-00$index",
          satisfied = satisfied,
          evidence = "Read repository evidence for criterion $index.",
          repairId = if (satisfied) null else "repair-$index",
          repairGuidance = if (satisfied) null else "Correct gap $index within the diagnosed scope.",
          pendingValidation = if (index == 2) "Run the downstream validation gate." else null,
        )
      },
      value = "Repository completeness assessment.",
    )

  private fun revision(number: Int, stage: AuditRepairStage): AuditRepairRevision = AuditRepairRevision(
    revision = number,
    requestId = "request-$number",
    stage = stage,
    recordedAt = "2026-09-12T10:00:00Z",
  )

  private val before = AuditRepairCheckpoint("before-checkpoint", "before-tree")
  private val after = AuditRepairCheckpoint("after-checkpoint", "after-tree")
}
