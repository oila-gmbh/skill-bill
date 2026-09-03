package skillbill.error

open class InvalidWorkflowStateSchemaError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

// SKILL-175 subtask 6: the prose feature-task engine is deleted; `mode='prose'` rows already
// persisted in `feature_task_workflows` remain readable for history (quarantine + loud-fail
// resume, per runtime-kotlin/agent/decisions.md), but every write path refuses new prose writes
// above the schema rather than silently reinterpreting or persisting them.
class ProseFeatureTaskWorkflowWriteRefusedError(
  val workflowId: String,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Feature-task workflow '$workflowId' write refused: mode=prose is retired. The prose engine " +
    "is deleted; re-run this feature on the runtime engine (mode=runtime) instead. Legacy " +
    "prose rows remain readable for history but no new prose writes are accepted.",
  cause,
)

class InvalidWorkListRowError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

class WorkflowIssueKeyConflictError(
  val workflowId: String,
  val persistedIssueKey: String,
  val requestedIssueKey: String,
) : ShellContentContractException(
  "Workflow '$workflowId' is already associated with issue key '$persistedIssueKey', not '$requestedIssueKey'.",
)

/**
 * SKILL-175: surfaced by every resume/continue/update path that encounters a
 * `feature_task_workflows` row whose `mode` decoded to
 * [skillbill.ports.workflow.model.FeatureTaskWorkflowMode.PROSE]. The prose engine is retired;
 * the in-flight prose row policy keeps such rows readable for history only, so a live path must
 * refuse loudly here instead of degrading or reinterpreting the row as a runtime row. The message
 * always names the one supported re-run path.
 */
class LegacyProseWorkflowError(
  val workflowId: String,
  val issueKey: String?,
) : ShellContentContractException(
  "Workflow '$workflowId' is a legacy prose-mode row; the prose engine is retired and this row " +
    "cannot be resumed, continued, or updated. Re-run this work through the runtime engine instead: " +
    "`skill-bill goal ${issueKey?.trim()?.ifEmpty { null } ?: "<ISSUE_KEY>"}`.",
)

class InvalidRejectedOutputDiagnosticSchemaError(message: String) :
  ShellContentContractException(message)

class InvalidProducerOutputEvidenceSchemaError(message: String) :
  ShellContentContractException(message)

/**
 * SKILL-174: surfaced when `orchestration/contracts/goal-planning-discovery-exclusions.yaml` is
 * missing from the classpath or fails its canonical Draft 2020-12 schema. Extends the governed
 * contract exception so the MCP server, the CLI config command, and the phase quarantine classifier
 * recognise it as a contract failure instead of letting it escape as an unmapped runtime crash.
 */
class InvalidGoalPlanningDiscoveryExclusionsSchemaError(message: String) :
  ShellContentContractException(message)

class InvalidGoalVerificationBoundaryCapsSchemaError(message: String) :
  ShellContentContractException(message)

class InvalidIssueKeySchemaError(message: String) :
  ShellContentContractException(message)

class GoalVerificationBoundaryCapExceededError(message: String) :
  ShellContentContractException(message)

/**
 * SKILL-51: surfaced when a parent decomposition manifest fails the
 * canonical `orchestration/contracts/decomposition-manifest-schema.yaml`
 * Draft 2020-12 schema or its Kotlin-enforced coherence checks. The
 * composed message carries the source label and the violation reason
 * so decomposition write/read seams fail loudly without conflating this
 * contract with durable workflow-state snapshots.
 */
class InvalidDecompositionManifestSchemaError(
  val sourceLabel: String,
  val reason: String,
  val failureCode: String? = null,
  cause: Throwable? = null,
) : ShellContentContractException(
  "Decomposition manifest '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation: $reason",
  cause,
)
