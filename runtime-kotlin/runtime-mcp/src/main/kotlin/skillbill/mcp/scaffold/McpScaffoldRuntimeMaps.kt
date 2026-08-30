package skillbill.mcp.scaffold

import skillbill.scaffold.model.ScaffoldResult

private const val SCAFFOLD_TELEMETRY_DURATION_SECONDS = 0

internal fun scaffoldSuccessMap(
  sessionId: String,
  payload: Map<String, Any?>,
  result: ScaffoldResult,
  dryRun: Boolean,
  orchestrated: Boolean,
): Map<String, Any?> {
  val outcome = if (dryRun) "dry-run" else "success"
  val baseTelemetryPayload =
    mapOf(
      "session_id" to sessionId,
      "kind" to result.kind,
      "skill_name" to result.skillName,
      "platform" to payload["platform"].orEmpty(),
      "family" to payload["family"].orEmpty(),
      "area" to payload["area"].orEmpty(),
      "result" to outcome,
      "duration_seconds" to SCAFFOLD_TELEMETRY_DURATION_SECONDS,
      "skill" to "skill-bill-scaffold",
    )
  return if (orchestrated) {
    mapOf(
      "mode" to "orchestrated",
      "telemetry_payload" to baseTelemetryPayload - "session_id",
      "skill_path" to result.skillPath.toString(),
      "notes" to result.notes,
    )
  } else {
    mapOf(
      "status" to "ok",
      "session_id" to sessionId,
      "skill_path" to result.skillPath.toString(),
      "notes" to result.notes,
    )
  }
}

internal fun scaffoldFailureMap(
  sessionId: String,
  payload: Map<String, Any?>,
  orchestrated: Boolean,
  error: Throwable,
): Map<String, Any?> = if (orchestrated) {
  mapOf(
    "mode" to "orchestrated",
    "telemetry_payload" to
      mapOf(
        "session_id" to sessionId,
        "kind" to payload["kind"].orEmpty(),
        "skill_name" to payload["name"].orEmpty(),
        "platform" to payload["platform"].orEmpty(),
        "family" to payload["family"].orEmpty(),
        "area" to payload["area"].orEmpty(),
        "result" to "failed",
        "duration_seconds" to SCAFFOLD_TELEMETRY_DURATION_SECONDS,
        "skill" to "skill-bill-scaffold",
        "error" to error.message.orEmpty(),
      ) - "session_id",
    "error" to error.message.orEmpty(),
  )
} else {
  mapOf(
    "status" to "error",
    "session_id" to sessionId,
    "error" to error.message.orEmpty(),
  )
}

private fun Any?.orEmpty(): String = this as? String ?: ""
