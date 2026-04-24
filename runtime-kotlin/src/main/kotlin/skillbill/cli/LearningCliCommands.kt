package skillbill.cli

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import me.tatarka.inject.annotations.Inject
import skillbill.app.AddLearningInput
import skillbill.app.EditLearningInput
import skillbill.app.LearningService
import skillbill.learnings.LearningScope

@Inject
class LearningsQueryCommands(
  val listCommand: LearningsListCommand,
  val showCommand: LearningsShowCommand,
  val resolveCommand: LearningsResolveCommand,
)

@Inject
class LearningsMutationCommands(
  val addCommand: LearningsAddCommand,
  val editCommand: LearningsEditCommand,
  val disableCommand: LearningsDisableCommand,
  val enableCommand: LearningsEnableCommand,
  val deleteCommand: LearningsDeleteCommand,
)

@Inject
class LearningsCommand(
  queryCommands: LearningsQueryCommands,
  mutationCommands: LearningsMutationCommands,
) : DocumentedNoOpCliCommand("learnings", "Manage local review learnings.") {
  init {
    subcommands(
      queryCommands.listCommand,
      queryCommands.showCommand,
      queryCommands.resolveCommand,
      mutationCommands.addCommand,
      mutationCommands.editCommand,
      mutationCommands.disableCommand,
      mutationCommands.enableCommand,
      mutationCommands.deleteCommand,
    )
  }
}

@Inject
class LearningsListCommand(
  private val service: LearningService,
  private val state: CliRunState,
) : DocumentedCliCommand("list", "List local learning entries.") {
  private val status by option("--status", help = "Learning status filter.").default("all")
  private val format by formatOption()

  override fun run() {
    val payload = service.list(status, state.dbOverride)
    if (format == CliFormat.JSON) {
      state.complete(payload, format)
    } else {
      @Suppress("UNCHECKED_CAST")
      state.completeText(CliOutput.learnings(payload["learnings"] as List<Map<String, Any?>>), payload)
    }
  }
}

@Inject
class LearningsShowCommand(
  private val service: LearningService,
  private val state: CliRunState,
) : DocumentedCliCommand("show", "Show a single learning entry.") {
  private val id by option("--id", help = "Learning id.").int().required()
  private val format by formatOption()

  override fun run() {
    state.complete(service.show(id, state.dbOverride), format)
  }
}

@Inject
class LearningsResolveCommand(
  private val service: LearningService,
  private val state: CliRunState,
) : DocumentedCliCommand("resolve", "Resolve active learnings for a review context.") {
  private val repo by option("--repo", help = "Optional repo scope key.")
  private val skill by option("--skill", help = "Optional review skill name.")
  private val reviewSessionId by option("--review-session-id", help = "Review session id for cross-event grouping.")
  private val format by formatOption()

  override fun run() {
    val payload = service.resolve(repo, skill, reviewSessionId, state.dbOverride)
    if (format == CliFormat.JSON) {
      state.complete(payload, format)
    } else {
      @Suppress("UNCHECKED_CAST")
      state.completeText(
        CliOutput.resolvedLearnings(
          payload["repo_scope_key"]?.toString(),
          payload["skill_name"]?.toString(),
          LearningScope.precedence,
          payload["learnings"] as List<Map<String, Any?>>,
        ),
        payload,
      )
    }
  }
}

@Inject
class LearningsAddCommand(
  private val service: LearningService,
  private val state: CliRunState,
) : DocumentedCliCommand("add", "Create a learning from a rejected review finding.") {
  private val scope by option("--scope").choice(
    "global" to LearningScope.GLOBAL,
    "repo" to LearningScope.REPO,
    "skill" to LearningScope.SKILL,
  ).default(LearningScope.GLOBAL)
  private val scopeKey by option("--scope-key").default("")
  private val title by option("--title").required()
  private val rule by option("--rule").required()
  private val reason by option("--reason").default("")
  private val fromRun by option("--from-run").required()
  private val fromFinding by option("--from-finding").required()
  private val format by formatOption()

  override fun run() {
    state.complete(
      service.add(AddLearningInput(scope, scopeKey, title, rule, reason, fromRun, fromFinding), state.dbOverride),
      format,
    )
  }
}

@Inject
class LearningsEditCommand(
  private val service: LearningService,
  private val state: CliRunState,
) : DocumentedCliCommand("edit", "Edit a local learning entry.") {
  private val id by option("--id").int().required()
  private val scope by option("--scope").choice(
    "global" to LearningScope.GLOBAL,
    "repo" to LearningScope.REPO,
    "skill" to LearningScope.SKILL,
  )
  private val scopeKey by option("--scope-key")
  private val title by option("--title")
  private val rule by option("--rule")
  private val reason by option("--reason")
  private val format by formatOption()

  override fun run() {
    require(listOf(scope, scopeKey, title, rule, reason).any { it != null }) {
      "Learning edit requires at least one field to update."
    }
    state.complete(service.edit(EditLearningInput(id, scope, scopeKey, title, rule, reason), state.dbOverride), format)
  }
}

@Inject
class LearningsDisableCommand(
  private val service: LearningService,
  private val state: CliRunState,
) : DocumentedCliCommand("disable", "Disable a learning entry.") {
  private val id by option("--id").int().required()
  private val format by formatOption()

  override fun run() {
    state.complete(service.setStatus(id, "disabled", state.dbOverride), format)
  }
}

@Inject
class LearningsEnableCommand(
  private val service: LearningService,
  private val state: CliRunState,
) : DocumentedCliCommand("enable", "Enable a disabled learning entry.") {
  private val id by option("--id").int().required()
  private val format by formatOption()

  override fun run() {
    state.complete(service.setStatus(id, "active", state.dbOverride), format)
  }
}

@Inject
class LearningsDeleteCommand(
  private val service: LearningService,
  private val state: CliRunState,
) : DocumentedCliCommand("delete", "Delete a learning entry.") {
  private val id by option("--id").int().required()
  private val format by formatOption()

  override fun run() {
    state.complete(service.delete(id, state.dbOverride), format)
  }
}
