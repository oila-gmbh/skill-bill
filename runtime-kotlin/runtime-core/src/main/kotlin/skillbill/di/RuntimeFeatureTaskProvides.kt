package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.application.review.ParallelCodeReviewRunner
import skillbill.application.runtime.RuntimeSingleton
import skillbill.engine.featuretask.FeatureTaskRuntimeReviewDriver
import skillbill.engine.featuretask.model.DefaultFeatureTaskRuntimePhaseGateBranchPort
import skillbill.engine.featuretask.model.DefaultFeatureTaskRuntimePhaseGateValidationPort
import skillbill.engine.featuretask.model.FeatureTaskRuntimePhaseGateBranchPort
import skillbill.engine.featuretask.model.FeatureTaskRuntimePhaseGateValidationPort
import skillbill.infrastructure.fs.FileSystemCheckedOutBranchSource
import skillbill.infrastructure.fs.FileSystemFeatureTaskRuntimeRunInvariantsSource
import skillbill.infrastructure.fs.FileSystemFeatureTaskRuntimeSpecStatusWriter
import skillbill.infrastructure.fs.JdkFeatureTaskRuntimeWorkerSupervisor
import skillbill.infrastructure.sqlite.SqliteFeatureTaskPhaseSettlementRepository
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.featuretask.FeatureTaskPhaseSettlementRepository
import skillbill.ports.system.CheckedOutBranchSource
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.taskruntime.FeatureTaskRuntimeSpecStatusWriter
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor

internal interface RuntimeFeatureTaskProvides {
  @Provides @JvmSynthetic
  fun featureTaskRuntimeReviewDriver(runner: ParallelCodeReviewRunner): FeatureTaskRuntimeReviewDriver =
    FeatureTaskRuntimeReviewDriver(runner::run)

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
