package skillbill.workflow.taskruntime.model

/**
 * A violated typed audit-repair rule. The exception message keeps the long-standing value-quoting text,
 * which belongs only in a private diagnostic row or a local log; [payloadFreeMessage] restates the same
 * rule and field with every agent-authored value omitted and is the only variant a retry prompt or an
 * operator surface may carry. It extends [IllegalArgumentException] so every existing `require`-shaped
 * catch and assertion keeps observing the same type.
 */
class FeatureTaskRuntimeAuditRepairRuleViolation(
  message: String,
  val payloadFreeMessage: String,
) : IllegalArgumentException(message)

/**
 * The payload-free restatement a consumer uses when a rejection reached it as a plain
 * [IllegalArgumentException] with no rule-level restatement of its own.
 */
const val FEATURE_TASK_RUNTIME_AUDIT_REPAIR_RULE_FAMILY: String =
  "the typed audit-repair rules govern gap and repair-item identity, uniqueness, declaration order, " +
    "bounded path and check references, and exact unmet-criterion coverage"
