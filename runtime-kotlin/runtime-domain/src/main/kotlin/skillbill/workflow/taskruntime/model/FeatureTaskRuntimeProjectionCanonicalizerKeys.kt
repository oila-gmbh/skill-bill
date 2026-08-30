package skillbill.workflow.taskruntime.model

internal val FEATURE_TASK_RUNTIME_NONBLANK_STRING_LIST_KEYS = setOf(
  "affected_boundaries",
  "patterns_and_decisions",
  "risks",
  "validation_strategy",
  "unresolved_questions",
  "evidence_refs",
  "unresolved_items",
  "target_paths_or_symbols",
  "test_obligations",
  "constraints",
)

internal val FEATURE_TASK_RUNTIME_RECONCILIATION_EVIDENCE_KEYS = setOf("reconciled", "evidence")

internal val FEATURE_TASK_RUNTIME_REPOSITORY_CHECKPOINT_KEYS =
  setOf("fingerprint", "base_ref", "head_ref", "working_tree_owned_paths")

internal val FEATURE_TASK_RUNTIME_PLAN_TASK_KEYS = setOf(
  "task_id",
  "depends_on",
  "description",
  "criterion_refs",
  "target_paths_or_symbols",
  "test_obligations",
  "constraints",
)

internal val FEATURE_TASK_RUNTIME_TASK_COMMITMENT_KEYS =
  setOf("task_id", "criterion_refs", "test_obligations", "constraints")

internal val FEATURE_TASK_RUNTIME_TEST_EXECUTION_KEYS = setOf("name", "outcome")

internal val FEATURE_TASK_RUNTIME_DEVIATION_KEYS = setOf("ref", "note")

internal val FEATURE_TASK_RUNTIME_TAB_RUN = Regex("\\t+")

internal val FEATURE_TASK_RUNTIME_ID_SEPARATOR_RUN = Regex("[\\s_]+")

internal val FEATURE_TASK_RUNTIME_ID_INVALID_CHAR = Regex("[^a-z0-9-]")

internal val FEATURE_TASK_RUNTIME_ID_HYPHEN_RUN = Regex("-{2,}")
