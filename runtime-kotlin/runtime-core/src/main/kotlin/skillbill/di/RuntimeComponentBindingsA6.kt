package skillbill.di

import skillbill.application.goalrunner.GoalRunnerChildRepairStore
import skillbill.application.goalrunner.WorkflowGoalRunnerManifestStore
import skillbill.application.goalrunner.WorkflowGoalRunnerOutcomeStore
import skillbill.infrastructure.fs.FileSystemDeclaredReviewSpecialists
import skillbill.infrastructure.fs.FileSystemInstalledPlatformPackCatalog
import skillbill.infrastructure.fs.FileSystemReviewLaunchAgentStaging
import skillbill.infrastructure.fs.GhGoalPullRequestPort
import skillbill.model.OptionalCallbacks
import skillbill.ports.goalrunner.runner.GoalPullRequestPort
import skillbill.ports.goalrunner.runner.GoalRunnerAttemptLedgerStore
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.review.DeclaredReviewSpecialistsPort
import skillbill.ports.review.ReviewLaunchAgentStagingPort
import skillbill.ports.scaffold.install.InstalledPlatformPackCatalogPort
import java.time.Clock

internal object RuntimeComponentBindingsA6 {
  internal fun runtimeClock(): Clock = Clock.systemUTC()

  internal fun reviewLaunchAgentStagingPort(
    adapter: FileSystemReviewLaunchAgentStaging,
  ): ReviewLaunchAgentStagingPort = adapter

  internal fun declaredReviewSpecialistsPort(
    adapter: FileSystemDeclaredReviewSpecialists,
  ): DeclaredReviewSpecialistsPort = adapter

  internal fun installedPlatformPackCatalogPort(
    adapter: FileSystemInstalledPlatformPackCatalog,
  ): InstalledPlatformPackCatalogPort = adapter

  internal fun goalRunnerManifestStore(adapter: WorkflowGoalRunnerManifestStore): GoalRunnerManifestStore = adapter

  internal fun goalRunnerWorkflowOutcomeStore(
    adapter: WorkflowGoalRunnerOutcomeStore,
  ): GoalRunnerWorkflowOutcomeStore = adapter

  internal fun goalRunnerAttemptLedgerStore(adapter: WorkflowGoalRunnerOutcomeStore): GoalRunnerAttemptLedgerStore =
    adapter

  internal fun goalRunnerChildRepairStore(adapter: WorkflowGoalRunnerOutcomeStore): GoalRunnerChildRepairStore = adapter

  internal fun goalPullRequestPort(callbacks: OptionalCallbacks, adapter: GhGoalPullRequestPort): GoalPullRequestPort =
    callbacks.goalPullRequestPort ?: adapter
}
