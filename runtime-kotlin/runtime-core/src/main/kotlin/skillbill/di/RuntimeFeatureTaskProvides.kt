package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.application.runtime.RuntimeSingleton
import skillbill.engine.featuretask.AuditRepairCheckpointCoordinator
import skillbill.engine.featuretask.DurableFeatureTaskRuntimeAcceptanceCriteriaSource
import skillbill.engine.featuretask.FeatureTaskLastCommitReviewDriver
import skillbill.engine.featuretask.FeatureTaskRuntimeAcceptanceCriteriaSource
import skillbill.engine.featuretask.FeatureTaskRuntimeReviewDriver
import skillbill.engine.featuretask.FeatureTaskRuntimeRunInvariantsStore
import skillbill.engine.featuretask.GitAuditRepairCheckpointCoordinator
import skillbill.engine.featuretask.model.DefaultFeatureTaskRuntimePhaseGateBranchPort
import skillbill.engine.featuretask.model.DefaultFeatureTaskRuntimePhaseGateValidationPort
import skillbill.engine.featuretask.model.FeatureTaskRuntimePhaseGateBranchPort
import skillbill.engine.featuretask.model.FeatureTaskRuntimePhaseGateValidationPort
import skillbill.infrastructure.fs.FileSystemCheckedOutBranchSource
import skillbill.infrastructure.fs.FileSystemFeatureTaskRuntimeRunInvariantsSource
import skillbill.infrastructure.fs.FileSystemFeatureTaskRuntimeSpecStatusWriter
import skillbill.infrastructure.fs.JdkFeatureTaskRuntimeWorkerSupervisor
import skillbill.infrastructure.sqlite.SqliteFeatureTaskPhaseSettlementRepository
import skillbill.infrastructure.sqlite.workflow.SqliteAuditRepairCycleRepository
import skillbill.model.RepositoryRoot
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.featuretask.AuditRepairCycleRepository
import skillbill.ports.featuretask.FeatureTaskPhaseSettlementRepository
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.system.CheckedOutBranchSource
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.taskruntime.FeatureTaskRuntimeSpecStatusWriter
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import java.time.Clock

internal interface RuntimeFeatureTaskProvides : RuntimeAuditRepairProvides {
  @Provides @JvmSynthetic
  fun featureTaskRuntimeReviewDriver(launcher: GoalRunnerSubtaskLauncher): FeatureTaskRuntimeReviewDriver =
    FeatureTaskLastCommitReviewDriver(launcher)

  @Provides @JvmSynthetic
  fun featureTaskPhaseSettlementRepository(database: DatabaseSessionFactory): FeatureTaskPhaseSettlementRepository =
    SqliteFeatureTaskPhaseSettlementRepository(database)

  @Provides @JvmSynthetic
  fun featureTaskRuntimeRunInvariantsSource(
    adapter: FileSystemFeatureTaskRuntimeRunInvariantsSource,
  ): FeatureTaskRuntimeRunInvariantsSource = adapter

  @Provides @RuntimeSingleton @JvmSynthetic
  fun featureTaskRuntimeWorkerSupervisor(
    adapter: JdkFeatureTaskRuntimeWorkerSupervisor,
  ): FeatureTaskRuntimeWorkerSupervisor = adapter

  @Provides @JvmSynthetic
  fun featureTaskRuntimeSpecStatusWriter(
    adapter: FileSystemFeatureTaskRuntimeSpecStatusWriter,
  ): FeatureTaskRuntimeSpecStatusWriter = adapter

  @Provides @JvmSynthetic
  fun featureTaskRuntimePhaseGateBranchPort(
    port: DefaultFeatureTaskRuntimePhaseGateBranchPort,
  ): FeatureTaskRuntimePhaseGateBranchPort = port

  @Provides @JvmSynthetic
  fun featureTaskRuntimePhaseGateValidationPort(
    port: DefaultFeatureTaskRuntimePhaseGateValidationPort,
  ): FeatureTaskRuntimePhaseGateValidationPort = port

  @Provides @JvmSynthetic
  fun checkedOutBranchSource(source: FileSystemCheckedOutBranchSource): CheckedOutBranchSource = source
}

internal interface RuntimeAuditRepairProvides {
  @Provides @JvmSynthetic
  fun auditRepairCycleRepository(database: DatabaseSessionFactory, clock: Clock): AuditRepairCycleRepository =
    SqliteAuditRepairCycleRepository(database, clock)

  @Provides @JvmSynthetic
  fun featureTaskRuntimeAcceptanceCriteriaSource(
    runInvariantsStore: FeatureTaskRuntimeRunInvariantsStore,
  ): FeatureTaskRuntimeAcceptanceCriteriaSource = DurableFeatureTaskRuntimeAcceptanceCriteriaSource(runInvariantsStore)

  @Provides @JvmSynthetic
  fun auditRepairCheckpointCoordinator(
    gitOperations: WorkflowGitOperations,
    repositoryRoot: RepositoryRoot,
  ): AuditRepairCheckpointCoordinator = GitAuditRepairCheckpointCoordinator(gitOperations, repositoryRoot)
}
