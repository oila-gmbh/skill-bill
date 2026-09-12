package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import skillbill.contracts.JsonCodec
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import skillbill.ports.workflow.gitops.CheckpointHistoryGitOperations
import skillbill.ports.workflow.gitops.UnavailableCheckpointHistoryGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path
import kotlin.test.assertEquals
import skillbill.contracts.workflow.AuditRepairCycleKeys as Keys

internal class AuditStageFixtureLauncher(
  private val delegate: AgentRunLauncher,
  private val context: () -> CliRuntimeContext,
) : AgentRunLauncher {
  override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome {
    val skill = request.skillRunRequest
    skill.auditRepairProviderSessionSink?.invoke("fixture-provider-session")
    val outcome = delegate.launch(request)
    if (skill.auditRepairExecutionId == null) return outcome
    if (outcome is AgentRunLaunchFacts && outcome.exitStatus == 0 && outcome.stdout.contains("satisfied")) {
      settle(skill.promptOverride.orEmpty())
    }
    return outcome
  }

  private fun settle(prompt: String) {
    val channel = prompt.substringAfter("## Durable audit-repair stage channel")
    fun field(key: String) = channel.lineSequence().first { it.startsWith("$key:") }.substringAfter(":").trim()
    val identity = listOf(Keys.WORKFLOW_ID, Keys.EXECUTION_ID, Keys.SESSION_ID, Keys.CYCLE_ID, Keys.OWNER_TOKEN)
      .associateWith(::field) + mapOf(
      Keys.AUDIT_ATTEMPT to field(Keys.AUDIT_ATTEMPT).toInt(),
      Keys.FENCING_GENERATION to field(Keys.FENCING_GENERATION).toLong(),
    )
    val refs = field(Keys.CRITERION_REFS).removeSurrounding("[", "]").split(", ").filter(String::isNotBlank)
    val checkpoint = JsonCodec.jsonElementToValue(
      requireNotNull(JsonCodec.parseObjectOrNull(field("diagnosis_checkpoint"))),
    )
    val assessment = mapOf(
      Keys.CHECKPOINT to checkpoint,
      Keys.CRITERIA to refs.map { ref ->
        mapOf(Keys.CRITERION_REF to ref, Keys.SATISFIED to true, Keys.EVIDENCE to "CLI fixture evidence")
      },
      Keys.VALUE to "{\"gaps\":[],\"non_blocking_findings\":[]}",
    )
    listOf("diagnosis", "checkpoint_pending", "final_audit", "satisfied").forEachIndexed { revision, stage ->
      val evidence = when (stage) {
        "diagnosis", "satisfied" -> mapOf(Keys.ASSESSMENT to assessment)
        "checkpoint_pending" -> mapOf(
          Keys.CHECKPOINT_INTENT to "cli-fixture-retain",
          Keys.REPOSITORY_FINGERPRINT to "test-repository-fingerprint",
          Keys.REPAIR_OUTCOMES to emptyList<Any>(),
        )
        else -> emptyMap()
      }
      val payload = identity + evidence + mapOf(
        Keys.CRITERION_REFS to refs,
        Keys.STAGE to stage,
        Keys.REVISION to revision,
        Keys.EXPECTED_REVISION to (revision - 1).coerceAtLeast(0),
        Keys.REQUEST_ID to "cli-fixture-$revision",
      )
      val result = CliRuntime.run(
        listOf("feature-task", "audit-stage", "--request-json", JsonCodec.mapToJsonString(payload)),
        context(),
      )
      assertEquals(0, result.exitCode, result.toString())
    }
  }
}

internal class CliAuditCheckpointOperations :
  CheckpointHistoryGitOperations by UnavailableCheckpointHistoryGitOperations {
  private val refs = mutableMapOf<String, String>()

  override fun createScopedCheckpoint(repoRoot: Path, paths: List<String>, message: String) =
    WorkflowGitOperationResult.Ok("3".repeat(40))

  override fun currentScopedContentFingerprint(repoRoot: Path, paths: List<String>) =
    WorkflowGitOperationResult.Ok("fixture-content")

  override fun retainedScopedContentFingerprint(repoRoot: Path, checkpointId: String) =
    WorkflowGitOperationResult.Ok("fixture-content")

  override fun resolveRef(repoRoot: Path, namespacePrefix: String, refName: String) =
    WorkflowGitOperationResult.Ok(refs[refName].orEmpty())

  override fun updateRef(
    repoRoot: Path,
    namespacePrefix: String,
    refName: String,
    targetSha: String,
  ): WorkflowGitOperationResult {
    refs[refName] = targetSha
    return WorkflowGitOperationResult.Ok(targetSha)
  }
}
