package skillbill.application.featuretask

import skillbill.ports.db.DatabaseSessionFactory

internal class FeatureTaskRuntimeReviewCheckpointRecorder(
  database: DatabaseSessionFactory,
  workflowPersistence: FeatureTaskRuntimeWorkflowPersistence,
  runtimeOwnedPersistence: RuntimeOwnedPersistenceBoundary,
) : FeatureTaskRuntimePhaseReviewCheckpointApi,
  FeatureTaskRuntimePhaseReviewGenerationApi by FeatureTaskRuntimeReviewGenerationRecorder(
    database,
    workflowPersistence,
    runtimeOwnedPersistence,
  ),
  FeatureTaskRuntimePhaseFindingVerificationApi by FeatureTaskRuntimeFindingVerificationRecorder(
    database,
    workflowPersistence,
  )
