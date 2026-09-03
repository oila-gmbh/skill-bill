package skillbill.di

import skillbill.infrastructure.fs.FileSystemDeclaredReviewSpecialists
import skillbill.infrastructure.fs.FileSystemInstalledPlatformPackCatalog
import skillbill.infrastructure.fs.FileSystemReviewLaunchAgentStaging
import skillbill.infrastructure.fs.GhGoalPullRequestPort
import skillbill.infrastructure.fs.JdkHostPlatformPort
import skillbill.infrastructure.sqlite.goalrunner.WorkflowGoalRunnerManifestStore
import skillbill.infrastructure.sqlite.goalrunner.WorkflowGoalRunnerOutcomeStore
import skillbill.model.OptionalCallbacks
import skillbill.ports.goalrunner.persistence.GoalRunnerChildRepairStore
import skillbill.ports.goalrunner.runner.GoalPullRequestPort
import skillbill.ports.goalrunner.runner.GoalRunnerAttemptLedgerStore
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.review.DeclaredReviewSpecialistsPort
import skillbill.ports.review.ReviewLaunchAgentStagingPort
import skillbill.ports.scaffold.install.InstalledPlatformPackCatalogPort
import skillbill.ports.system.HostPlatformPort
import java.time.Clock

internal object RuntimeComponentBindingsA6 {
  internal fun runtimeClock(): Clock = Clock.systemUTC()

  internal fun hostPlatformPort(callbacks: OptionalCallbacks): HostPlatformPort =
    callbacks.hostPlatformPort ?: JdkHostPlatformPort

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
