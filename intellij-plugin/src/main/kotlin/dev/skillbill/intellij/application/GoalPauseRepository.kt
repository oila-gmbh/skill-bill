package dev.skillbill.intellij.application

import java.nio.file.Path

/**
 * Port for requesting a durable, graceful goal pause.
 *
 * This is the plugin's only non-read-only contract. It requests a pause at the next
 * boundary — the runtime consumes it after the running subtask reaches durable terminal
 * success — so it never interrupts work in flight and never kills a process. Callers
 * must treat a successful request as "requested", not "paused".
 */
fun interface GoalPauseRepository {
    suspend fun requestPause(projectRoot: Path, issueKey: String): GoalPauseOutcome
}

sealed class GoalPauseOutcome {
    /** The runtime durably recorded the request; the pause lands at the next boundary. */
    data object Requested : GoalPauseOutcome()

    /** The request did not land. [summary] is safe to show; it never carries process output. */
    data class Failed(val summary: String) : GoalPauseOutcome()
}
