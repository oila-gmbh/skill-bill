package skillbill.application.work

import skillbill.application.model.IdeStatusFreshness
import skillbill.application.model.IdeStatusLifecycleState
import skillbill.application.model.IdeStatusProblem
import skillbill.application.model.IdeStatusProblemCode
import skillbill.application.model.IdeStatusSnapshot
import skillbill.application.model.IdeStatusStep
import java.time.Instant

internal object IdeStatusProblemSnapshots {
  fun invalidRepositoryInput(observedAt: Instant, message: String): IdeStatusSnapshot =
    problemSnapshot(
      repositoryIdentity = "repo-root-realpath-v1:invalid",
      observedAt = observedAt,
      code = IdeStatusProblemCode.INVALID_REPOSITORY_INPUT,
      message = message,
      summary = message,
      stepLabel = "Invalid repository",
    )

  fun missingRepositoryIdentity(observedAt: Instant, message: String): IdeStatusSnapshot =
    problemSnapshot(
      repositoryIdentity = "repo-root-realpath-v1:missing",
      observedAt = observedAt,
      code = IdeStatusProblemCode.MISSING_REPOSITORY_IDENTITY,
      message = message,
      summary = message,
      stepLabel = "Missing repository identity",
    )

  fun absentDatabase(repositoryIdentity: String, observedAt: Instant): IdeStatusSnapshot =
    problemSnapshot(
      repositoryIdentity = repositoryIdentity,
      observedAt = observedAt,
      code = IdeStatusProblemCode.ABSENT_DATABASE,
      message = "Skill Bill database is not present.",
      summary = "Skill Bill database is not present for this repository.",
      stepLabel = "Database unavailable",
      exitLifecycle = IdeStatusLifecycleState.IDLE,
      freshness = IdeStatusFreshness.UNKNOWN,
    )

  fun noMatchingWork(repositoryIdentity: String, observedAt: Instant): IdeStatusSnapshot =
    problemSnapshot(
      repositoryIdentity = repositoryIdentity,
      observedAt = observedAt,
      code = IdeStatusProblemCode.NO_MATCHING_WORK,
      message = "No matching Skill Bill work for this repository.",
      summary = "No matching Skill Bill work for this repository.",
      stepLabel = "No matching work",
    )

  fun incompatibleRecord(
    repositoryIdentity: String,
    observedAt: Instant,
    message: String,
    workflowId: String? = null,
  ): IdeStatusSnapshot =
    problemSnapshot(
      repositoryIdentity = repositoryIdentity,
      observedAt = observedAt,
      code = IdeStatusProblemCode.INCOMPATIBLE_RECORD,
      message = message,
      summary = message,
      stepLabel = "Incompatible record",
      workflowId = workflowId,
      exitLifecycle = IdeStatusLifecycleState.IDLE,
      freshness = IdeStatusFreshness.UNKNOWN,
    )

  private fun problemSnapshot(
    repositoryIdentity: String,
    observedAt: Instant,
    code: IdeStatusProblemCode,
    message: String,
    summary: String,
    stepLabel: String,
    workflowId: String? = null,
    exitLifecycle: IdeStatusLifecycleState = IdeStatusLifecycleState.IDLE,
    freshness: IdeStatusFreshness = IdeStatusFreshness.FRESH,
  ): IdeStatusSnapshot = IdeStatusSnapshot(
    repositoryIdentity = repositoryIdentity,
    workflowId = workflowId,
    lifecycleState = exitLifecycle,
    currentStep = IdeStatusStep(id = "none", label = stepLabel),
    updatedAt = observedAt,
    freshness = freshness,
    summary = summary,
    problem = IdeStatusProblem(code = code, message = message),
  )
}

internal fun IdeStatusSnapshot.exitCode(): Int = when (problem?.code) {
  IdeStatusProblemCode.INVALID_REPOSITORY_INPUT,
  IdeStatusProblemCode.MISSING_REPOSITORY_IDENTITY,
  IdeStatusProblemCode.INCOMPATIBLE_RECORD,
  IdeStatusProblemCode.SCHEMA_INCOMPATIBLE,
  -> 1
  IdeStatusProblemCode.ABSENT_DATABASE,
  IdeStatusProblemCode.NO_MATCHING_WORK,
  null,
  -> 0
}
