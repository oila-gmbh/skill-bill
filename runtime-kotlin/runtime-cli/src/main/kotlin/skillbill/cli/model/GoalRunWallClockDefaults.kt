package skillbill.cli.model

/**
 * Default per-subtask wall-clock cap. Chosen from local goal_subtask_events telemetry:
 * p95 ≈ 96m, p99 ≈ 142m, observed max ≈ 178m. A live forever-command (e.g. attached
 * `docker compose up`) is spared by the progress-idle timeout, so this hard ceiling is
 * what stops unbounded waits. Pass 0 on the CLI to disable.
 */
internal const val DEFAULT_GOAL_MAX_WALL_CLOCK_MINUTES = 180
