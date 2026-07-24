package skillbill.config.model

import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition

const val COMPACTION_KEY: String = "compaction"

/**
 * Phase-agent context compaction is on by default: a phase inherits a 1M-context model whose own
 * auto-compaction trigger sits near the top of that window, so a phase that peaks well below it
 * never compacts and pays the full quadratic re-read of its own history.
 */
const val DEFAULT_COMPACTION_ENABLED: Boolean = true
const val DEFAULT_COMPACTION_WINDOW_TOKENS: Int = 400_000
const val DEFAULT_COMPACTION_TRIGGER_PCT: Int = 70

/**
 * Compaction that fires below this many accumulated tokens thrashes: a phase reading ordinary
 * source files refills the freed context within a few turns, and the provider aborts the run after
 * three consecutive refills. The floor is enforced on the effective trigger rather than the window
 * so a large window paired with a small percentage cannot smuggle a thrashing configuration in.
 */
const val MIN_COMPACTION_TRIGGER_TOKENS: Int = 200_000

private const val PERCENT_SCALE: Int = 100
private val VALID_TRIGGER_PCT: IntRange = 1..PERCENT_SCALE

data class PhaseCompactionDirective(
  val windowTokens: Int,
  val triggerPct: Int,
) {
  val triggerTokens: Int get() = windowTokens / PERCENT_SCALE * triggerPct

  init {
    require(windowTokens > 0) { "PhaseCompactionDirective.windowTokens must be positive." }
    require(triggerPct in VALID_TRIGGER_PCT) {
      "PhaseCompactionDirective.triggerPct must be between ${VALID_TRIGGER_PCT.first} and ${VALID_TRIGGER_PCT.last}."
    }
  }
}

data class CompactionSettings(
  val enabled: Boolean = DEFAULT_COMPACTION_ENABLED,
  val windowTokens: Int = DEFAULT_COMPACTION_WINDOW_TOKENS,
  val triggerPct: Int = DEFAULT_COMPACTION_TRIGGER_PCT,
  val phases: Map<String, PhaseCompactionDirective> = emptyMap(),
) {
  fun directiveFor(phaseId: String): PhaseCompactionDirective? {
    if (!enabled) return null
    return phases[phaseId] ?: PhaseCompactionDirective(windowTokens, triggerPct)
  }

  companion object {
    val DEFAULT: CompactionSettings = CompactionSettings()
  }
}

sealed interface CompactionSettingsParse {
  data class Valid(val settings: CompactionSettings) : CompactionSettingsParse

  data class Invalid(
    val keyPath: String,
    val value: String,
    val reason: String,
  ) : CompactionSettingsParse
}

fun parseCompactionSettings(raw: Any?): CompactionSettingsParse = try {
  CompactionSettingsParse.Valid(parseCompactionMapping(raw))
} catch (failure: InvalidCompactionSettings) {
  failure.invalid
}

private fun parseCompactionMapping(raw: Any?): CompactionSettings {
  val root = raw as? Map<*, *> ?: invalidCompaction(COMPACTION_KEY, raw, "must be a mapping.")
  val fields = root.entries.associate { (key, value) -> key.toString() to value }
  fields.entries.firstOrNull { (key, _) -> key !in COMPACTION_FIELDS }?.let { (key, value) ->
    invalidCompaction("$COMPACTION_KEY.$key", value, "is not a supported compaction field.")
  }

  val enabled = when (val value = fields[ENABLED_KEY]) {
    null -> DEFAULT_COMPACTION_ENABLED
    is Boolean -> value
    else -> invalidCompaction("$COMPACTION_KEY.$ENABLED_KEY", value, "must be a boolean.")
  }
  val windowTokens = intField(COMPACTION_KEY, fields, WINDOW_KEY, DEFAULT_COMPACTION_WINDOW_TOKENS)
  val triggerPct = intField(COMPACTION_KEY, fields, TRIGGER_PCT_KEY, DEFAULT_COMPACTION_TRIGGER_PCT)
  requireSaneTrigger(COMPACTION_KEY, windowTokens, triggerPct)

  return CompactionSettings(
    enabled = enabled,
    windowTokens = windowTokens,
    triggerPct = triggerPct,
    phases = parsePhases(fields[PHASES_KEY], windowTokens, triggerPct),
  )
}

private fun parsePhases(raw: Any?, defaultWindow: Int, defaultPct: Int): Map<String, PhaseCompactionDirective> {
  if (raw == null) return emptyMap()
  val phases = raw as? Map<*, *> ?: invalidCompaction("$COMPACTION_KEY.$PHASES_KEY", raw, "must be a mapping.")
  return phases.entries.associate { (rawPhaseId, rawDirective) ->
    val phaseId = rawPhaseId as? String ?: invalidCompaction(
      "$COMPACTION_KEY.$PHASES_KEY.$rawPhaseId",
      rawDirective,
      "is not a runtime phase.",
    )
    if (phaseId !in FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds) {
      invalidCompaction("$COMPACTION_KEY.$PHASES_KEY.$phaseId", rawDirective, "is not a runtime phase.")
    }
    val path = "$COMPACTION_KEY.$PHASES_KEY.$phaseId"
    val directive = rawDirective as? Map<*, *> ?: invalidCompaction(path, rawDirective, "must be a mapping.")
    val fields = directive.entries.associate { (key, value) -> key.toString() to value }
    fields.entries.firstOrNull { (key, _) -> key !in PHASE_DIRECTIVE_FIELDS }?.let { (key, value) ->
      invalidCompaction("$path.$key", value, "is not a supported phase compaction field.")
    }
    val windowTokens = intField(path, fields, WINDOW_KEY, defaultWindow)
    val triggerPct = intField(path, fields, TRIGGER_PCT_KEY, defaultPct)
    requireSaneTrigger(path, windowTokens, triggerPct)
    phaseId to PhaseCompactionDirective(windowTokens = windowTokens, triggerPct = triggerPct)
  }
}

private fun intField(path: String, fields: Map<String, Any?>, key: String, fallback: Int): Int {
  val value = fields[key] ?: return fallback
  val number = (value as? Number)?.takeIf { it.toDouble() == it.toInt().toDouble() }
    ?: invalidCompaction("$path.$key", value, "must be a whole number.")
  return number.toInt()
}

private fun requireSaneTrigger(path: String, windowTokens: Int, triggerPct: Int) {
  if (windowTokens <= 0) {
    invalidCompaction("$path.$WINDOW_KEY", windowTokens, "must be a positive number of tokens.")
  }
  if (triggerPct !in VALID_TRIGGER_PCT) {
    invalidCompaction(
      "$path.$TRIGGER_PCT_KEY",
      triggerPct,
      "must be between ${VALID_TRIGGER_PCT.first} and ${VALID_TRIGGER_PCT.last}.",
    )
  }
  val trigger = windowTokens / PERCENT_SCALE * triggerPct
  if (trigger < MIN_COMPACTION_TRIGGER_TOKENS) {
    invalidCompaction(
      "$path.$WINDOW_KEY",
      windowTokens,
      "yields a $trigger-token compaction trigger at $triggerPct%, below the " +
        "$MIN_COMPACTION_TRIGGER_TOKENS-token floor; a trigger this low refills within a few turns and the " +
        "provider aborts the run as thrashing.",
    )
  }
}

private fun invalidCompaction(keyPath: String, value: Any?, reason: String): Nothing = throw InvalidCompactionSettings(
  CompactionSettingsParse.Invalid(keyPath = keyPath, value = value?.toString() ?: "null", reason = reason),
)

private class InvalidCompactionSettings(
  val invalid: CompactionSettingsParse.Invalid,
) : RuntimeException()

private const val ENABLED_KEY: String = "enabled"
private const val WINDOW_KEY: String = "window_tokens"
private const val TRIGGER_PCT_KEY: String = "trigger_pct"
private const val PHASES_KEY: String = "phases"
private val COMPACTION_FIELDS: Set<String> = setOf(ENABLED_KEY, WINDOW_KEY, TRIGGER_PCT_KEY, PHASES_KEY)
private val PHASE_DIRECTIVE_FIELDS: Set<String> = setOf(WINDOW_KEY, TRIGGER_PCT_KEY)
