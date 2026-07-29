@file:Suppress("TooManyFunctions")

package skillbill.workflow.taskruntime.model

import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError

const val IMPLEMENTATION_COMPLETION_DECISION_CONTRACT_VERSION: String = "0.1"

sealed interface ImplementationCompletionOutcome {
  val canAdvance: Boolean
}

data class ImplementationCompleted(
  val completedTaskIds: List<String>,
) : ImplementationCompletionOutcome {
  override val canAdvance: Boolean = true

  init {
    require(completedTaskIds.isNotEmpty()) {
      "ImplementationCompleted.completedTaskIds must be non-empty."
    }
  }
}

data class ImplementationIncomplete(
  val missingTaskIds: List<String>,
  val unknownTaskIds: List<String>,
  val unresolvedItems: List<String>,
  val actionableDeviations: List<ActionableDeviation>,
  val openObligationIds: List<String>,
) : ImplementationCompletionOutcome {
  override val canAdvance: Boolean = false

  init {
    require(
      missingTaskIds.isNotEmpty() ||
        unknownTaskIds.isNotEmpty() ||
        unresolvedItems.isNotEmpty() ||
        actionableDeviations.isNotEmpty() ||
        openObligationIds.isNotEmpty(),
    ) {
      "ImplementationIncomplete must specify at least one blocker: missing task ID, " +
        "unknown task ID, unresolved item, actionable deviation, or open durable obligation."
    }
  }
}

data class ActionableDeviation(
  val ref: String,
  val note: String,
) {
  init {
    require(ref.isNotBlank()) { "ActionableDeviation.ref must be non-blank." }
    require(note.isNotBlank()) { "ActionableDeviation.note must be non-blank." }
  }
}

sealed interface ImplementationCompletionDisposition {
  val isTerminal: Boolean
  val isSemanticContinuation: Boolean
}

data object ImplementationAdvance : ImplementationCompletionDisposition {
  override val isTerminal: Boolean = true
  override val isSemanticContinuation: Boolean = false
}

data class SemanticIncompleteWorkContinuation(
  val priorReceipt: FeatureTaskRuntimeImplementationReceipt,
  val failureDisposition: FeatureTaskRuntimeFailureDisposition,
) : ImplementationCompletionDisposition {
  override val isTerminal: Boolean = false
  override val isSemanticContinuation: Boolean = true
}

data class SchemaInvalidCorrection(
  val schemaViolation: String,
) : ImplementationCompletionDisposition {
  override val isTerminal: Boolean = false
  override val isSemanticContinuation: Boolean = false

  init {
    require(schemaViolation.isNotBlank()) {
      "SchemaInvalidCorrection.schemaViolation must be non-blank."
    }
  }
}

data class TerminalBlocked(
  val reason: String,
  val disposition: FeatureTaskRuntimeFailureDisposition,
) : ImplementationCompletionDisposition {
  override val isTerminal: Boolean = true
  override val isSemanticContinuation: Boolean = false

  init {
    require(reason.isNotBlank()) { "TerminalBlocked.reason must be non-blank." }
  }
}

data class GovernedReplan(
  val planDigest: String,
) : ImplementationCompletionDisposition {
  override val isTerminal: Boolean = true
  override val isSemanticContinuation: Boolean = false

  init {
    require(planDigest.isNotBlank()) { "GovernedReplan.planDigest must be non-blank." }
  }
}

data class GovernedDecomposition(
  val manifestDigest: String,
) : ImplementationCompletionDisposition {
  override val isTerminal: Boolean = true
  override val isSemanticContinuation: Boolean = false

  init {
    require(manifestDigest.isNotBlank()) { "GovernedDecomposition.manifestDigest must be non-blank." }
  }
}

data class ImplementationCompletionDecision(
  val outcome: ImplementationCompletionOutcome,
  val disposition: ImplementationCompletionDisposition,
) {
  val canAdvance: Boolean = outcome.canAdvance && disposition === ImplementationAdvance

  fun exactBlockingReason(): String? = when {
    outcome is ImplementationIncomplete && disposition !is SemanticIncompleteWorkContinuation ->
      when (disposition) {
        is TerminalBlocked -> disposition.reason
        is GovernedReplan ->
          "implementation requested governed replan with plan digest ${disposition.planDigest}"
        is GovernedDecomposition ->
          "implementation requested governed decomposition with manifest digest ${disposition.manifestDigest}"
        is SchemaInvalidCorrection ->
          "implementation has schema-invalid output: ${disposition.schemaViolation}"
        ImplementationAdvance ->
          "implementation is incomplete and cannot advance"
      }
    outcome is ImplementationCompleted && !outcome.canAdvance ->
      "implementation reported completed but completion decision denied advancement"
    else -> null
  }

  fun exactMissingTaskId(): String? = when (outcome) {
    is ImplementationIncomplete -> outcome.missingTaskIds.firstOrNull()
    is ImplementationCompleted -> null
  }

  fun exactUnresolvedField(): String? = when (outcome) {
    is ImplementationIncomplete -> outcome.unresolvedItems.firstOrNull()
      ?: outcome.actionableDeviations.firstOrNull()?.let { "${it.ref}: ${it.note}" }
      ?: outcome.unknownTaskIds.firstOrNull()?.let { "unknown completed task ID: $it" }
      ?: outcome.openObligationIds.firstOrNull()?.let { "open durable obligation: $it" }
    is ImplementationCompleted -> null
  }

  companion object {
    fun complete(completedTaskIds: List<String>): ImplementationCompletionDecision = ImplementationCompletionDecision(
      outcome = ImplementationCompleted(completedTaskIds),
      disposition = ImplementationAdvance,
    )

    fun incompleteContinue(
      priorReceipt: FeatureTaskRuntimeImplementationReceipt,
      failureDisposition: FeatureTaskRuntimeFailureDisposition,
      missingTaskIds: List<String> = emptyList(),
      unknownTaskIds: List<String> = emptyList(),
      unresolvedItems: List<String> = emptyList(),
      actionableDeviations: List<ActionableDeviation> = emptyList(),
      openObligationIds: List<String> = emptyList(),
    ): ImplementationCompletionDecision = ImplementationCompletionDecision(
      outcome = ImplementationIncomplete(
        missingTaskIds = missingTaskIds,
        unknownTaskIds = unknownTaskIds,
        unresolvedItems = unresolvedItems,
        actionableDeviations = actionableDeviations,
        openObligationIds = openObligationIds,
      ),
      disposition = SemanticIncompleteWorkContinuation(
        priorReceipt = priorReceipt,
        failureDisposition = failureDisposition,
      ),
    )

    fun schemaInvalid(schemaViolation: String): ImplementationCompletionDecision = ImplementationCompletionDecision(
      outcome = ImplementationIncomplete(
        missingTaskIds = emptyList(),
        unknownTaskIds = emptyList(),
        unresolvedItems = listOf("schema violation"),
        actionableDeviations = emptyList(),
        openObligationIds = emptyList(),
      ),
      disposition = SchemaInvalidCorrection(schemaViolation),
    )

    fun terminalBlocked(
      reason: String,
      disposition: FeatureTaskRuntimeFailureDisposition,
    ): ImplementationCompletionDecision = ImplementationCompletionDecision(
      outcome = ImplementationIncomplete(
        missingTaskIds = listOf("terminal policy outcome"),
        unknownTaskIds = emptyList(),
        unresolvedItems = emptyList(),
        actionableDeviations = emptyList(),
        openObligationIds = emptyList(),
      ),
      disposition = TerminalBlocked(reason, disposition),
    )

    fun governedReplan(planDigest: String): ImplementationCompletionDecision = ImplementationCompletionDecision(
      outcome = ImplementationIncomplete(
        missingTaskIds = listOf("governed replan"),
        unknownTaskIds = emptyList(),
        unresolvedItems = emptyList(),
        actionableDeviations = emptyList(),
        openObligationIds = emptyList(),
      ),
      disposition = GovernedReplan(planDigest),
    )

    fun governedDecomposition(manifestDigest: String): ImplementationCompletionDecision =
      ImplementationCompletionDecision(
        outcome = ImplementationIncomplete(
          missingTaskIds = listOf("governed decomposition"),
          unknownTaskIds = emptyList(),
          unresolvedItems = emptyList(),
          actionableDeviations = emptyList(),
          openObligationIds = emptyList(),
        ),
        disposition = GovernedDecomposition(manifestDigest),
      )
  }
}

data class ImplementationCompletionContext(
  val authoritativeExecutablePlan: FeatureTaskRuntimeExecutablePlan,
  val unresolvedObligations: UnresolvedConvergence,
  val receipt: FeatureTaskRuntimeImplementationReceipt,
  val governedOutcome: GovernedImplementationOutcome? = null,
) {
  fun completionDecision(): ImplementationCompletionDecision {
    governedOutcome?.let {
      return when (it) {
        is GovernedImplementationOutcome.Replan -> ImplementationCompletionDecision.governedReplan(it.planDigest)
        is GovernedImplementationOutcome.Decomposition ->
          ImplementationCompletionDecision.governedDecomposition(it.manifestDigest)
      }
    }
    val authoritativeTaskIds = authoritativeExecutablePlan.tasks.map { it.taskId }
    val completedTaskIds = receipt.completedTaskIds
    val missingTaskIds = authoritativeTaskIds - completedTaskIds.toSet()
    val unknownCompletedTaskIds = completedTaskIds - authoritativeTaskIds.toSet()
    val unresolvedItems = receipt.unresolvedItems
    val actionableDeviations = receipt.deviations.map {
      ActionableDeviation(ref = it.ref, note = it.note)
    }

    val openObligationIds = unresolvedObligations.implementationObligations
      .filter { it.status == ConvergenceStatus.OPEN }
      .map { it.logicalId }
      .sorted()

    val cannotAdvance = missingTaskIds.isNotEmpty() ||
      unknownCompletedTaskIds.isNotEmpty() ||
      unresolvedItems.isNotEmpty() ||
      actionableDeviations.isNotEmpty() ||
      openObligationIds.isNotEmpty()

    return if (cannotAdvance) {
      ImplementationCompletionDecision.incompleteContinue(
        priorReceipt = receipt,
        failureDisposition = receiptToFailureDisposition(receipt),
        missingTaskIds = missingTaskIds,
        unknownTaskIds = unknownCompletedTaskIds,
        unresolvedItems = unresolvedItems,
        actionableDeviations = actionableDeviations,
        openObligationIds = openObligationIds,
      )
    } else {
      ImplementationCompletionDecision.complete(completedTaskIds)
    }
  }

  private fun receiptToFailureDisposition(
    receipt: FeatureTaskRuntimeImplementationReceipt,
  ): FeatureTaskRuntimeFailureDisposition = when {
    receipt.testsExecuted.any { it.outcome == FeatureTaskRuntimeTestOutcome.FAILED } ->
      FeatureTaskRuntimeFailureDisposition.RETRYABLE
    else -> FeatureTaskRuntimeFailureDisposition.RETRYABLE
  }
}

sealed interface GovernedImplementationOutcome {
  data class Replan(val planDigest: String) : GovernedImplementationOutcome
  data class Decomposition(val manifestDigest: String) : GovernedImplementationOutcome
}

fun implementationCompletionDecisionFromContext(
  authoritativeExecutablePlan: FeatureTaskRuntimeExecutablePlan,
  unresolvedObligations: UnresolvedConvergence,
  receipt: FeatureTaskRuntimeImplementationReceipt,
  governedOutcome: GovernedImplementationOutcome? = null,
): ImplementationCompletionDecision = ImplementationCompletionContext(
  authoritativeExecutablePlan = authoritativeExecutablePlan,
  unresolvedObligations = unresolvedObligations,
  receipt = receipt,
  governedOutcome = governedOutcome,
).completionDecision()

fun validateImplementationReceiptAgainstPlan(
  receipt: FeatureTaskRuntimeImplementationReceipt,
  plan: FeatureTaskRuntimeExecutablePlan,
  unresolvedObligations: UnresolvedConvergence,
) {
  val planTaskIds = plan.tasks.map { it.taskId }.toSet()
  val completedTaskIds = receipt.completedTaskIds.toSet()

  val unknownTaskIds = completedTaskIds - planTaskIds
  if (unknownTaskIds.isNotEmpty()) {
    throw InvalidFeatureTaskRuntimePlanningProjectionSchemaError(
      sourceLabel = "<implementation_receipt.completed_task_ids>",
      reason = "receipt claims completion of unknown task IDs: ${unknownTaskIds.joinToString()}",
    )
  }

  val missingTaskIds = planTaskIds - completedTaskIds
  val hasUnresolvedItems = receipt.unresolvedItems.isNotEmpty()
  val hasActionableDeviations = receipt.deviations.isNotEmpty()

  if (missingTaskIds.isEmpty() && !hasUnresolvedItems && !hasActionableDeviations) {
    return
  }

  val hasOpenObligations = unresolvedObligations.implementationObligations.any {
    it.status == ConvergenceStatus.OPEN
  }

  val hasAnyBlocker = missingTaskIds.isNotEmpty() ||
    hasUnresolvedItems ||
    hasActionableDeviations ||
    hasOpenObligations

  if (hasAnyBlocker) {
    val blockers = buildList {
      if (missingTaskIds.isNotEmpty()) {
        add("missing plan task IDs: ${missingTaskIds.joinToString()}")
      }
      if (hasUnresolvedItems) {
        add("unresolved items: ${receipt.unresolvedItems.joinToString()}")
      }
      if (hasActionableDeviations) {
        add("actionable deviations: ${receipt.deviations.size}")
      }
      if (hasOpenObligations) {
        add("open unresolved obligations")
      }
    }
    throw InvalidFeatureTaskRuntimePlanningProjectionSchemaError(
      sourceLabel = "<implementation_receipt>",
      reason = "receipt cannot advance: ${blockers.joinToString("; ")}",
    )
  }
}
