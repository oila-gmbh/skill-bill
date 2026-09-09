package skillbill.cli

import skillbill.application.review.simulateGovernedEvidenceReads
import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.infrastructure.fs.GitWorkflowGitOperations
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.ExecutableLookup
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import skillbill.ports.review.ReviewNativeAgentPreflightPort
import skillbill.ports.telemetry.RemoteTransportPort
import skillbill.ports.telemetry.UnconfiguredRemoteTransportPort
import skillbill.ports.workflow.gitops.CheckpointHistoryGitOperationsProvider
import skillbill.ports.workflow.gitops.GoalSubtaskReviewGitOperations
import skillbill.ports.workflow.gitops.GoalSubtaskReviewGitOperationsProvider
import skillbill.ports.workflow.gitops.RepositoryFingerprintGitOperations
import skillbill.ports.workflow.gitops.RepositoryFingerprintGitOperationsProvider
import skillbill.ports.workflow.gitops.RepositoryOwnedPathsGitOperations
import skillbill.ports.workflow.gitops.RepositoryOwnedPathsGitOperationsProvider
import skillbill.ports.workflow.gitops.ScopedStagingGitOperations
import skillbill.ports.workflow.gitops.ScopedStagingGitOperationsProvider
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationStatus
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksResult
import skillbill.ports.workflow.gitops.model.WorkflowWorktreeActivityResult
import skillbill.workflow.goal.model.GoalObservabilityChangedFileSummary
import skillbill.workflow.goal.model.GoalObservabilityDiffStat
import skillbill.workflow.goal.model.GoalObservabilitySelectedDiffHunksimport java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals

internal data class FeatureTaskRuntimeCliContextOptions(
  var environment: Map<String, String> = emptyMap(),
  var liveStdout: (String) -> Unit = {},
  var liveStderr: (String) -> Unit = {},
  var workflowGitOperations: WorkflowGitOperations = FakeRuntimeGitOperations(),
  var requester: RemoteTransportPort = UnconfiguredRemoteTransportPort,
)

internal data class FeatureTaskRuntimeCliFixture(
  val tempDir: Path,
  val dbPath: Path,
  val specPath: Path,
) {
  private var implementationWrites = 0
  val reviewBaseSha: String get() = GitWorkflowGitOperations().headCommitSha(tempDir).value.trim()

  fun context(
    launcher: AgentRunLauncher,
    configure: FeatureTaskRuntimeCliContextOptions.() -> Unit = {},
  ): CliRuntimeContext = context(launcher, FeatureTaskRuntimeCliContextOptions().apply(configure))

  fun context(launcher: AgentRunLauncher, options: FeatureTaskRuntimeCliContextOptions): CliRuntimeContext {
    (options.workflowGitOperations as? FakeRuntimeGitOperations)?.prepareRepo(tempDir)
    return CliRuntimeContext(
      userHome = tempDir.also { installFakeRuntimeMcpBin(it) },
      agentRunLauncher = implementationWritingLauncher(launcher),
      environment = options.environment,
      requester = options.requester,
      liveStdout = options.liveStdout,
      liveStderr = options.liveStderr,
      workflowGitOperations = options.workflowGitOperations,
      executableLookup = ExecutableLookup { true },
      reviewNativeAgentPreflight = ReviewNativeAgentPreflightPort.NONE,
    )
  }

  private fun implementationWritingLauncher(launcher: AgentRunLauncher): AgentRunLauncher =
    object : AgentRunLauncher by launcher {
      override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome {
        val phase = PHASE_LINE.find(request.skillRunRequest.promptOverride.orEmpty())?.groupValues?.get(1)
        if (phase == "implement") {
          implementationWrites += 1
          Files.writeString(tempDir.resolve("src/Foo.kt"), "class Foo { val implementation = $implementationWrites }\n")
        }
        return launcher.launch(request)
      }
    }

  fun materializeDatabaseWithTelemetry(level: String, requester: RemoteTransportPort) = materializeTelemetryDatabase(
    tempDir,
    dbPath,
    level,
    context(RecordingPhaseLauncher()) { this.requester = requester },
  )

  fun runCommand(extra: List<String> = emptyList()): List<String> = buildList {
    add("--db")
    add(dbPath.toString())
    add("feature-task")
    add("SKILL-650")
    add(specPath.toString())
    add("--repo-root")
    add(tempDir.toString())
    addAll(extra)
  }

  fun resumeCommand(workflowId: String, selectionJson: String? = null, agentId: String = "codex"): List<String> =
    buildList {
      addAll(
        listOf(
          "--db",
          dbPath.toString(),
          "feature-task",
          "resume",
          workflowId,
          "SKILL-650",
          specPath.toString(),
          "--repo-root",
          tempDir.toString(),
          "--agent",
          agentId,
        ),
      )
      selectionJson?.let {
        add("--agent-addon-selection-json")
        add(it)
      }
    }
}

internal fun featureTaskCommand(fixture: FeatureTaskRuntimeCliFixture, command: String): List<String> = listOf(
  "--db",
  fixture.dbPath.toString(),
  command,
  "SKILL-650",
  fixture.specPath.toString(),
  "--repo-root",
  fixture.tempDir.toString(),
  "--agent",
  "codex",
)

internal fun goalContinuationValidationDepth(dbPath: Path, workflowId: String): String =
  requireNotNull(goalContinuationArtifact(dbPath, workflowId)?.get("validation_depth") as? String) {
    "goal_continuation.validation_depth missing for $workflowId"
  }
internal fun goalContinuationArtifact(dbPath: Path, workflowId: String): Map<String, Any?>? {
  val artifacts = featureTaskWorkflowArtifacts(dbPath, workflowId)
  return JsonSupport.anyToStringAnyMap(artifacts["goal_continuation"])
}
internal fun runInvariantsCodeReviewMode(dbPath: Path, workflowId: String): String {
  val invariants = requireNotNull(
    featureTaskWorkflowArtifacts(dbPath, workflowId)["feature_task_runtime_run_invariants"] as? Map<*, *>,
  ) {
    "feature_task_runtime_run_invariants missing for $workflowId"
  }
  return requireNotNull(invariants["code_review_mode"] as? String) {
    "feature_task_runtime_run_invariants.code_review_mode missing for $workflowId"
  }
}
internal fun featureTaskWorkflowArtifacts(dbPath: Path, workflowId: String): Map<String, Any?> {
  val artifactsJson = DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
    connection.prepareStatement(
      "SELECT artifacts_json FROM feature_task_workflows WHERE workflow_id = ?",
    ).use { statement ->
      statement.setString(1, workflowId)
      statement.executeQuery().use { rs ->
        check(rs.next()) { "workflow $workflowId missing" }
        rs.getString(1)
      }
    }
  }
  return requireNotNull(
    JsonSupport.anyToStringAnyMap(
      JsonSupport.jsonElementToValue(requireNotNull(JsonSupport.parseObjectOrNull(artifactsJson))),
    ),
  ) { "artifacts_json for $workflowId is not an object map" }
}

internal fun normalizeRuntimeStdout(stdout: String): String = stdout
  .lines()
  .joinToString("\n") { line ->
    if (line.startsWith("workflow_id:")) "workflow_id: <normalized>" else line
  }

internal fun runtimeFixture(specFileName: String = "spec.md"): FeatureTaskRuntimeCliFixture {
  val tempDir = Files.createTempDirectory("skillbill-cli-feature-task-runtime")
  FakeRuntimeGitOperations().prepareRepo(tempDir)
  val specPath = tempDir.resolve(".feature-specs/SKILL-650-runtime/$specFileName")
  Files.createDirectories(specPath.parent)
  Files.writeString(
    specPath,
    """
    # SKILL-650 runtime spec

    Feature size: SMALL

    ## Acceptance Criteria

    1. The runtime drives every ordered phase to a validated output.
    2. The CLI delegates to the application runner without owning orchestration.

    ## Mandates and Overrides

    - Stay on the experimental path only when explicitly requested.
    """.trimIndent(),
  )
  return FeatureTaskRuntimeCliFixture(
    tempDir = tempDir,
    dbPath = tempDir.resolve("metrics.db"),
    specPath = specPath,
  )
}

internal fun writeExecutionBudgetAddon(repo: Path) =
  writeAgentAddon(repo, "execution-budget", "Execution budget fixture guidance.")

internal fun writeAgentAddon(repo: Path, slug: String, guidance: String) {
  val root = repo.resolve("agent-addons/$slug")
  Files.createDirectories(root)
  Files.writeString(
    root.resolve("agent-addon.yaml"),
    """
      |contract_version: "1.0"
      |slug: $slug
      |description: $slug fixture.
      |agent_ids: [codex]
      |consumers: [bill-feature]
    """.trimMargin(),
  )
  Files.writeString(root.resolve("content.md"), "## Boundary\n\n$guidance\n")
}

internal fun resolvedSelectionJson(
  fixture: FeatureTaskRuntimeCliFixture,
  receivingAgent: String,
  vararg slugs: String = arrayOf("execution-budget"),
): String {
  val result = CliRuntime.run(
    buildList {
      addAll(
        listOf(
          "agent-addon",
          "resolve-selection",
          "--repo-root",
          fixture.tempDir.toString(),
          "--receiving-agent",
          receivingAgent,
          "--format",
          "json",
        ),
      )
      slugs.forEach { slug -> addAll(listOf("--token", "agent-addon:$slug")) }
    },
    fixture.context(RecordingPhaseLauncher()),
  )
  assertEquals(0, result.exitCode, result.stdout)
  return result.stdout.trim()
}

internal val PHASE_LINE = Regex("^Phase: ([a-z_-]+) ", setOf(RegexOption.MULTILINE))

internal fun phaseIdFromPromptOrNull(prompt: String): String? = PHASE_LINE.find(prompt)?.groupValues?.get(1)

internal class RecordingPhaseLauncher(
  internal val invalidFromLaunchIndex: Int? = null,
  internal val invalidReviewUntilLaunchIndex: Int? = null,
  internal val decomposePlan: Boolean = false,
) : AgentRunLauncher {
  val requests: MutableList<AgentRunLaunchRequest> = CopyOnWriteArrayList()

  fun phaseOrder(): List<String> = requests.mapNotNull { request ->
    PHASE_LINE.find(request.skillRunRequest.promptOverride.orEmpty())?.groupValues?.get(1)
  }

  override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome {
    val launchIndex = requests.size
    requests += request
    val prompt = request.skillRunRequest.promptOverride.orEmpty()
    val phaseId = PHASE_LINE.find(prompt)?.groupValues?.get(1)
    if (phaseId == null) {
      simulateGovernedEvidenceReads(request.skillRunRequest)
      return AgentRunLaunchFacts(
        agent = InstallAgent.fromNormalizedId(request.agentId, label = "agentId"),
        exitStatus = 0,
        stdout = "NO_FINDINGS",
        stderr = "",
        timedOut = false,
        spawnFailed = false,
      )
    }
    val invalid = (invalidFromLaunchIndex?.let { launchIndex >= it } ?: false) ||
      isInvalidReviewRetry(launchIndex)
    val stdout = when {
      invalid -> INVALID_PHASE_OUTPUT
      decomposePlan && phaseId == "plan" -> DECOMPOSE_PLAN_OUTPUT
      else -> validPhaseOutput(phaseId)
    }
    return AgentRunLaunchFacts(
      agent = InstallAgent.fromNormalizedId(request.agentId, label = "agentId"),
      exitStatus = 0,
      stdout = stdout,
      stderr = "",
      timedOut = false,
      spawnFailed = false,
    )
  }

  internal fun isInvalidReviewRetry(launchIndex: Int): Boolean {
    val limit = invalidReviewUntilLaunchIndex ?: return false
    val phaseId = PHASE_LINE.find(requests[launchIndex].skillRunRequest.promptOverride.orEmpty())
      ?.groupValues?.get(1)
    return launchIndex < limit && phaseId == "review"
  }

  companion object {
    const val INVALID_PHASE_OUTPUT = "not a json object"

    fun validPhaseOutput(phaseId: String): String {
      val producedOutputs = when (phaseId) {
        "review" -> "findings: []"
        "audit" -> """{value: "{\"gaps\":[],\"non_blocking_findings\":[]}"}"""
        "verify_findings" -> "finding_dispositions: []"
        "preplan" -> PREPLAN_DIGEST_OUTPUTS
        "plan" -> PLAN_PROSE_OUTPUTS
        "implement" -> IMPLEMENT_PROSE_OUTPUTS
        "validate" -> VALIDATION_RESULT_OUTPUTS
        "write_history" -> HISTORY_RESULT_OUTPUTS
        "commit_push" -> COMMIT_PUSH_RESULT_OUTPUTS
        else -> """tasks: ["task-1"]"""
      }
      val phaseVerdict = when (phaseId) {
        "audit" -> "verdict: \"satisfied\""
        "verify_findings" -> "verdict: \"no_findings_verified\""
        else -> ""
      }
      val base =
        """
        contract_version: "$FEATURE_TASK_RUNTIME_CONTRACT_VERSION"
        phase_id: "$phaseId"
        status: "completed"
        summary: "Phase produced a validated output."
        $phaseVerdict
        produced_outputs:
          $producedOutputs
        """.trimIndent()
      return base
    }

    internal const val PREPLAN_DIGEST_OUTPUTS: String =
      """{value: "Fixture preplan prose for downstream plan."}"""

    internal const val PLAN_PROSE_OUTPUTS: String =
      """{value: "Fixture plan prose for downstream implement and audit."}"""

    internal const val IMPLEMENT_PROSE_OUTPUTS: String =
      """{value: "Fixture implement prose for downstream audit."}"""

    internal const val VALIDATION_RESULT_OUTPUTS: String =
      """{validation_result: {validation_status: "passed", checks: ["FooTest"], """ +
        """repository_checkpoint: {fingerprint: "fixture-checkpoint-1"}, """ +
        """gate_run_count: 1, gate_runs: [{duration_ms: 1, outcome: "passed", """ +
        """cache_mode: "forced_full", executed_work_units: 1}]}}"""

    internal const val HISTORY_RESULT_OUTPUTS: String =
      """{history_result: {changed_paths: ["agent/history.md"], decisions_recorded: []}}"""

    internal const val COMMIT_PUSH_RESULT_OUTPUTS: String =
      """{commit_push_result: {message: "SKILL-650: runtime cli fixture subtask", """ +
        """changed_paths: ["src/Foo.kt"], """ +
        """branch: "feat/pre-created-runtime-branch", base_branch: "main", pushed: true}}"""

    fun validPhaseOutputForTest(phaseId: String): String = validPhaseOutput(phaseId)

    val DECOMPOSE_PLAN_OUTPUT: String = """
      {
        "contract_version": "$FEATURE_TASK_RUNTIME_CONTRACT_VERSION",
        "phase_id": "plan",
        "status": "completed",
        "summary": "Plan needs ordered subtasks.",
        "produced_outputs": {
          "value": "Decompose into ordered subtasks.",
          "decomposition_package": {
            "mode": "decompose",
            "reason": "Plan needs ordered subtasks.",
            "feature_name": "runtime cli decomposition",
            "parent_spec_overview": "Split the CLI runtime work into ordered subtasks.",
            "validation_strategy": "bill-code-check",
            "base_branch": "main",
            "feature_branch": "feat/SKILL-650-runtime-cli-decomposition",
            "subtasks": [
              {
                "id": 1,
                "name": "first",
                "scope": "First subtask.",
                "acceptance_criteria": ["First criterion."],
                "non_goals": [],
                "dependency_notes": "First.",
                "validation_strategy": "unit tests",
                "next_path": "Work subtask 2.",
                "depends_on": []
              },
              {
                "id": 2,
                "name": "second",
                "scope": "Second subtask.",
                "acceptance_criteria": ["Second criterion."],
                "non_goals": [],
                "dependency_notes": "Depends on first.",
                "validation_strategy": "unit tests",
                "next_path": "Finish.",
                "depends_on": [1]
              }
            ]
          }
        }
      }
    """.trimIndent()
  }
}

internal class InterruptAtImplementLauncher : AgentRunLauncher {
  val requests: MutableList<AgentRunLaunchRequest> = mutableListOf()

  override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome {
    requests += request
    val phaseId = phaseIdFromPromptOrNull(request.skillRunRequest.promptOverride.orEmpty())
    if (phaseId == null) {
      simulateGovernedEvidenceReads(request.skillRunRequest)
      return AgentRunLaunchFacts(
        agent = InstallAgent.fromNormalizedId(request.agentId, label = "agentId"),
        exitStatus = 0,
        stdout = "NO_FINDINGS",
        stderr = "",
        timedOut = false,
        spawnFailed = false,
      )
    }
    if (phaseId == "implement") {
      return AgentRunLaunchFacts(
        agent = InstallAgent.CODEX,
        exitStatus = null,
        stdout = "",
        stderr = "interrupted after completed planning",
        timedOut = false,
        spawnFailed = true,
      )
    }
    return AgentRunLaunchFacts(
      agent = InstallAgent.CODEX,
      exitStatus = 0,
      stdout = RecordingPhaseLauncher.validPhaseOutputForTest(phaseId),
      stderr = "",
      timedOut = false,
      spawnFailed = false,
    )
  }

  fun phaseOrder(): List<String> = requests.mapNotNull { request ->
    phaseIdFromPromptOrNull(request.skillRunRequest.promptOverride.orEmpty())
  }
}

internal val ALL_PHASES =
  listOf(
    "preplan",
    "plan",
    "implement",
    "audit",
    "review",
    "verify_findings",
    "implement_fix",
    "build",
    "validate",
    "write_history",
    "commit_push",
    "pr",
  )
internal val AGENT_LAUNCHED_PHASES = ALL_PHASES.filterNot { it == "review" || it == "implement_fix" || it == "build" }

internal fun goalChildLaunchedPhases(): List<String> = AGENT_LAUNCHED_PHASES.filterNot { it == "pr" || it == "build" }

internal const val GOAL_CHILD_COMPLETED_PHASES =
  "preplan, plan, implement, audit, review, verify_findings, validate, write_history, commit_push"
internal const val COMPLETED_PHASES_CLEAN_RUN =
  "preplan, plan, implement, audit, review, verify_findings, validate, write_history, commit_push, pr"

internal class FakeRuntimeGitOperations(
  internal var currentBranchValue: String = "feat/pre-created-runtime-branch",
  internal val checkoutResult: WorkflowGitOperationResult? = null,
  internal val trackedDelta: String = "",
  private val git: GitWorkflowGitOperations = GitWorkflowGitOperations(),
) : WorkflowGitOperations by git,
  GoalSubtaskReviewGitOperationsProvider,
  CheckpointHistoryGitOperationsProvider,
  RepositoryFingerprintGitOperationsProvider,
  RepositoryOwnedPathsGitOperationsProvider,
  ScopedStagingGitOperationsProvider {
  override val repositoryOwnedPathsOperations: RepositoryOwnedPathsGitOperations = git.repositoryOwnedPathsOperations
  override val repositoryFingerprintOperations: RepositoryFingerprintGitOperations = TestRepositoryFingerprintOperations

  override val scopedStagingOperations: ScopedStagingGitOperations = object : ScopedStagingGitOperations {
    override fun stagePaths(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult =
      WorkflowGitOperationResult.Ok(value = "")

    override fun captureIndexState(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult =
      WorkflowGitOperationResult.Ok(value = "")

    override fun restoreIndexState(repoRoot: Path, paths: List<String>, snapshot: String): WorkflowGitOperationResult =
      WorkflowGitOperationResult.Ok(value = "")

    override fun stagedPaths(repoRoot: Path): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = "")

    override fun pathContentIdentities(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult =
      WorkflowGitOperationResult.Ok(
        value = paths.joinToString(separator = "\u0000") { path -> "identity\t$path" },
      )
  }
  val checkoutBranches: MutableList<String> = mutableListOf()
  private var committed = false

  fun prepareRepo(repoRoot: Path) {
    if (!Files.exists(repoRoot.resolve(".git"))) {
      runGit(repoRoot, "init", "-b", currentBranchValue)
      runGit(repoRoot, "config", "user.email", "skill-bill@example.test")
      runGit(repoRoot, "config", "user.name", "Skill Bill")
      runGit(repoRoot, "config", "commit.gpgsign", "false")
      Files.writeString(repoRoot.resolve(".gitignore"), "*\n!src/\n!src/**\n!.gitignore\n!root.txt\n")
      Files.writeString(repoRoot.resolve("root.txt"), "baseline\n")
      Files.createDirectories(repoRoot.resolve("src"))
      Files.writeString(repoRoot.resolve("src/Foo.kt"), "class Foo\n")
      runGit(repoRoot, "add", ".gitignore", "root.txt", "src/Foo.kt")
      runGit(repoRoot, "commit", "-m", "baseline")
    }
    runGit(repoRoot, "checkout", "-B", currentBranchValue)
  }

  override fun checkoutBranch(repoRoot: Path, branch: String, baseBranch: String?): WorkflowGitOperationResult {
    checkoutBranches += branch
    val result = checkoutResult ?: WorkflowGitOperationResult(status = "ok", value = branch)
    if (result.ok) {
      runGit(repoRoot, "checkout", "-B", branch)
      currentBranchValue = branch
    }
    return result
  }

  override fun branchExists(repoRoot: Path, branch: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "true")

  override fun createCommit(repoRoot: Path, message: String): WorkflowGitOperationResult =
    git.createCommit(repoRoot, message).also { if (it.ok) committed = true }

  override fun localBranchHasUnpushedCommits(repoRoot: Path, branch: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = committed.toString())

  override fun pushBranch(repoRoot: Path, branch: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = branch)

  override fun pushBranchWithLease(repoRoot: Path, branch: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Ok(value = branch)

  override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = "")

  override fun resolveCommit(repoRoot: Path, revision: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Ok(
      value = revision.takeIf { it.matches(Regex("^[0-9a-fA-F]{40,64}$")) } ?: "1".repeat(40),
    )
  override fun validateBranchBase(
    repoRoot: Path,
    branch: String,
    expectedBaseBranch: String,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult(status = "ok", value = expectedBaseBranch)

  private fun runGit(repoRoot: Path, vararg args: String) {
    val process = ProcessBuilder(listOf("git", "-C", repoRoot.toString()) + args)
      .redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { output }
  }
}
