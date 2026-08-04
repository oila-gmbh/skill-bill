package skillbill.config.model

import skillbill.install.model.InstallAgent
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition

const val EXECUTION_MATRIX_KEY: String = "execution_matrix"

enum class ExecutionTier(
  val id: String,
) {
  REASONING("reasoning"),
  IMPLEMENTATION("implementation"),
  ;

  companion object {
    fun fromId(id: String): ExecutionTier? = entries.firstOrNull { it.id == id }
  }
}

val DEFAULT_PHASE_TIERS: Map<String, ExecutionTier> = mapOf(
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN to ExecutionTier.IMPLEMENTATION,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN to ExecutionTier.REASONING,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT to ExecutionTier.IMPLEMENTATION,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX to ExecutionTier.IMPLEMENTATION,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW to ExecutionTier.REASONING,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT to ExecutionTier.REASONING,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE to ExecutionTier.REASONING,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY to ExecutionTier.IMPLEMENTATION,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH to ExecutionTier.IMPLEMENTATION,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR to ExecutionTier.IMPLEMENTATION,
)

data class PhaseModelDirective(
  val model: String,
  val effort: String? = null,
  val profile: String? = null,
) {
  init {
    require(model.isNotBlank()) { "PhaseModelDirective.model must be non-blank." }
    effort?.let { require(it.isNotBlank()) { "PhaseModelDirective.effort must be non-blank when provided." } }
    profile?.let { require(it.isNotBlank()) { "PhaseModelDirective.profile must be non-blank when provided." } }
  }
}

data class ExecutionMatrixSessionOverlay(
  val phaseTiers: Map<String, ExecutionTier>? = null,
  val agents: Map<InstallAgent, Map<ExecutionTier, PhaseModelDirective>>? = null,
)

data class ExecutionMatrix(
  val phaseTiers: Map<String, ExecutionTier> = emptyMap(),
  val agents: Map<InstallAgent, Map<ExecutionTier, PhaseModelDirective>> = emptyMap(),
  val sessions: Map<String, ExecutionMatrixSessionOverlay> = emptyMap(),
) {
  fun tierOf(phaseId: String): ExecutionTier = phaseTiers[phaseId] ?: DEFAULT_PHASE_TIERS.getValue(phaseId)

  fun directiveFor(agentId: String, phaseId: String): PhaseModelDirective? {
    val agent = InstallAgent.entries.firstOrNull { it.id == agentId.trim().lowercase() } ?: return null
    return agents[agent]?.get(tierOf(phaseId))
  }

  /**
   * Applies a per-session matrix overlay selected by name. Returns `this` for a null, blank, or
   * undeclared selector. A declared overlay whole-field replaces the default matrix fields (an
   * absent overlay field yields an empty map, not the default matrix field) and clears the sessions
   * map. Selection emits no degradation record; the selector is a plain value.
   */
  fun selectSession(sessionName: String?): ExecutionMatrix {
    if (sessionName.isNullOrBlank()) return this
    val overlay = sessions[sessionName] ?: return this
    return ExecutionMatrix(
      phaseTiers = overlay.phaseTiers ?: emptyMap(),
      agents = overlay.agents ?: emptyMap(),
    )
  }
}

sealed interface ExecutionMatrixParse {
  data class Valid(val matrix: ExecutionMatrix) : ExecutionMatrixParse

  data class Invalid(
    val keyPath: String,
    val value: String,
    val reason: String,
  ) : ExecutionMatrixParse
}

fun parseExecutionMatrix(raw: Any?): ExecutionMatrixParse = try {
  ExecutionMatrixParse.Valid(parseExecutionMatrixMapping(raw))
} catch (failure: InvalidExecutionMatrix) {
  failure.invalid
}

private fun parseExecutionMatrixMapping(raw: Any?): ExecutionMatrix {
  val matrix = raw as? Map<*, *> ?: invalidExecutionMatrix(EXECUTION_MATRIX_KEY, raw, "must be a mapping.")
  val fields = matrix.entries.associate { (key, value) -> key.toString() to value }
  fields.entries.firstOrNull { (key, _) -> key !in EXECUTION_MATRIX_FIELDS }?.let { (key, value) ->
    invalidExecutionMatrix("$EXECUTION_MATRIX_KEY.$key", value, "is not a supported execution_matrix field.")
  }
  val phaseTiers = if (PHASE_TIERS_KEY in fields) {
    parsePhaseTiers("$EXECUTION_MATRIX_KEY.$PHASE_TIERS_KEY", fields[PHASE_TIERS_KEY])
  } else {
    emptyMap()
  }
  val sessions = if (SESSIONS_KEY in fields) parseSessions(fields[SESSIONS_KEY]) else emptyMap()
  return ExecutionMatrix(
    phaseTiers = phaseTiers,
    agents = parseAgents("$EXECUTION_MATRIX_KEY.$AGENTS_KEY", fields[AGENTS_KEY]),
    sessions = sessions,
  )
}

private fun parsePhaseTiers(basePath: String, raw: Any?): Map<String, ExecutionTier> {
  val phaseTiers = raw as? Map<*, *> ?: invalidExecutionMatrix(basePath, raw, "must be a mapping.")
  return phaseTiers.entries.associate { (rawPhaseId, rawTier) ->
    val phaseId = rawPhaseId as? String ?: invalidExecutionMatrix(
      "$basePath.$rawPhaseId",
      rawTier,
      "is not a runtime phase.",
    )
    if (phaseId !in FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds) {
      invalidExecutionMatrix("$basePath.$phaseId", rawTier, "is not a runtime phase.")
    }
    val tier = ExecutionTier.fromId(rawTier as? String ?: "") ?: invalidExecutionMatrix(
      "$basePath.$phaseId",
      rawTier,
      "must be reasoning or implementation.",
    )
    phaseId to tier
  }
}

private fun parseAgents(basePath: String, raw: Any?): Map<InstallAgent, Map<ExecutionTier, PhaseModelDirective>> {
  val agents = raw as? Map<*, *> ?: invalidExecutionMatrix(basePath, raw, "must be a mapping.")
  return agents.entries.associate { (rawAgentId, rawTiers) ->
    val agentId = rawAgentId as? String ?: invalidExecutionMatrix(
      "$basePath.$rawAgentId",
      rawTiers,
      "is not a supported install agent.",
    )
    val agent = InstallAgent.entries.firstOrNull { it.id == agentId } ?: invalidExecutionMatrix(
      "$basePath.$agentId",
      rawTiers,
      "is not a supported install agent.",
    )
    agent to parseAgentTiers(basePath, agentId, rawTiers)
  }
}

private fun parseAgentTiers(basePath: String, agentId: String, raw: Any?): Map<ExecutionTier, PhaseModelDirective> {
  val tiers = raw as? Map<*, *> ?: invalidExecutionMatrix("$basePath.$agentId", raw, "must be a mapping.")
  return tiers.entries.associate { (rawTierId, rawDirective) ->
    val tierId = rawTierId as? String
    val tier = tierId?.let(ExecutionTier::fromId) ?: invalidExecutionMatrix(
      "$basePath.$agentId.$rawTierId",
      rawDirective,
      "must be reasoning or implementation.",
    )
    tier to parseDirective(basePath, agentId, tier, rawDirective)
  }
}

private fun parseDirective(basePath: String, agentId: String, tier: ExecutionTier, raw: Any?): PhaseModelDirective {
  val path = "$basePath.$agentId.${tier.id}"
  val directive = raw as? Map<*, *> ?: invalidExecutionMatrix(path, raw, "must be a mapping.")
  val fields = directive.entries.associate { (key, value) -> key.toString() to value }
  if (MODEL_KEY !in fields) invalidExecutionMatrix("$path.$MODEL_KEY", "<missing>", "is required.")
  val model = fields[MODEL_KEY] as? String
  if (model.isNullOrBlank()) {
    invalidExecutionMatrix("$path.$MODEL_KEY", fields[MODEL_KEY], "must be a non-blank string.")
  }
  val effort = fields[EFFORT_KEY]
  if (EFFORT_KEY in fields && (effort !is String || effort.isBlank())) {
    invalidExecutionMatrix("$path.$EFFORT_KEY", effort, "must be a non-blank string when provided.")
  }
  val profile = fields[PROFILE_KEY]
  if (PROFILE_KEY in fields && (profile !is String || profile.isBlank())) {
    invalidExecutionMatrix("$path.$PROFILE_KEY", profile, "must be a non-blank string when provided.")
  }
  fields.entries.firstOrNull { (key, _) -> key !in DIRECTIVE_FIELDS }?.let { (key, value) ->
    invalidExecutionMatrix("$path.$key", value, "is not a supported tier field.")
  }
  return PhaseModelDirective(model = model, effort = effort as? String, profile = profile as? String)
}

private fun parseSessions(raw: Any?): Map<String, ExecutionMatrixSessionOverlay> {
  val sessions = raw as? Map<*, *> ?: invalidExecutionMatrix(
    "$EXECUTION_MATRIX_KEY.$SESSIONS_KEY",
    raw,
    "must be a mapping.",
  )
  return sessions.entries.associate { (rawName, rawOverlay) ->
    val name = rawName as? String ?: invalidExecutionMatrix(
      "$EXECUTION_MATRIX_KEY.$SESSIONS_KEY.$rawName",
      rawOverlay,
      "is not a non-blank session name.",
    )
    if (name.isBlank()) {
      invalidExecutionMatrix(
        "$EXECUTION_MATRIX_KEY.$SESSIONS_KEY.$name",
        rawOverlay,
        "is not a non-blank session name.",
      )
    }
    name to parseSessionOverlay(name, rawOverlay)
  }
}

private fun parseSessionOverlay(name: String, raw: Any?): ExecutionMatrixSessionOverlay {
  val path = "$EXECUTION_MATRIX_KEY.$SESSIONS_KEY.$name"
  val overlay = raw as? Map<*, *> ?: invalidExecutionMatrix(path, raw, "must be a mapping.")
  val fields = overlay.entries.associate { (key, value) -> key.toString() to value }
  fields.entries.firstOrNull { (key, _) -> key !in SESSION_OVERLAY_FIELDS }?.let { (key, value) ->
    invalidExecutionMatrix("$path.$key", value, "is not a supported session overlay field.")
  }
  return ExecutionMatrixSessionOverlay(
    phaseTiers = if (PHASE_TIERS_KEY in fields) {
      parsePhaseTiers("$path.$PHASE_TIERS_KEY", fields[PHASE_TIERS_KEY])
    } else {
      null
    },
    agents = if (AGENTS_KEY in fields) {
      parseAgents("$path.$AGENTS_KEY", fields[AGENTS_KEY])
    } else {
      null
    },
  )
}

private fun invalidExecutionMatrix(keyPath: String, value: Any?, reason: String): Nothing =
  throw InvalidExecutionMatrix(
    ExecutionMatrixParse.Invalid(keyPath = keyPath, value = value?.toString() ?: "null", reason = reason),
  )

private class InvalidExecutionMatrix(
  val invalid: ExecutionMatrixParse.Invalid,
) : RuntimeException()

private const val PHASE_TIERS_KEY: String = "phase_tiers"
private const val AGENTS_KEY: String = "agents"
private const val SESSIONS_KEY: String = "sessions"
private const val MODEL_KEY: String = "model"
private const val EFFORT_KEY: String = "effort"
private const val PROFILE_KEY: String = "profile"
private val EXECUTION_MATRIX_FIELDS: Set<String> = setOf(PHASE_TIERS_KEY, AGENTS_KEY, SESSIONS_KEY)
private val DIRECTIVE_FIELDS: Set<String> = setOf(MODEL_KEY, EFFORT_KEY, PROFILE_KEY)
private val SESSION_OVERLAY_FIELDS: Set<String> = setOf(PHASE_TIERS_KEY, AGENTS_KEY)
