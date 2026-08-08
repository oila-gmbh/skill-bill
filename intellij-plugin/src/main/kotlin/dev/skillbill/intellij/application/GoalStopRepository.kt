package dev.skillbill.intellij.application

import java.nio.file.Path

/**
 * Port for stopping a running goal now.
 *
 * The plugin never terminates a process itself: it asks the runtime to record the
 * operator stop durably and then terminate its own runner. A successful call means the
 * runtime accepted the request, not that the widget may assert a new lifecycle — the
 * next status snapshot stays authoritative.
 */
fun interface GoalStopRepository {
    suspend fun requestStop(projectRoot: Path, issueKey: String): GoalStopOutcome
}

sealed class GoalStopOutcome {
    /** The runtime accepted the stop and owns the termination that follows. */
    data object Requested : GoalStopOutcome()

    /** The request did not land. [summary] is safe to show; it never carries process output. */
    data class Failed(val summary: String) : GoalStopOutcome()
}
