package skillbill.workflow.taskruntime.model

data class PhaseHandoffProjectionShape(
  val projectionName: String,
  val projectionContractId: String,
  val projectionContractVersion: String,
  val promptVisibility: FeatureTaskRuntimeHandoffPromptVisibility,
  val budget: FeatureTaskRuntimeHandoffProjectionBudget,
  val declaredFieldNames: List<String>,
)

data class PhaseHandoffProjectionDelivery(
  val checkpointPolicy: FeatureTaskRuntimeRepositoryCheckpointPolicy =
    FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED,
  val required: Boolean = true,
  val allowsPrivateArtifactReference: Boolean = false,
  val inlineAlternative: FeatureTaskRuntimeCompactReferenceKind? = null,
  val producerIteration: FeatureTaskRuntimeProducerIteration? = null,
  val authorizedReferenceKinds: Set<FeatureTaskRuntimeCompactReferenceKind> = emptySet(),
)
