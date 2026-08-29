package skillbill.application.featuretask.validation

import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FeatureTaskRuntimeBuildReceiptSchemaValidator
import kotlin.test.Test
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateRunRecord

class FeatureTaskRuntimeBuildGateReceiptProjectionTest {
  @Test
  fun `settled build gate coordinator output validates against build_receipt contract`() {
    val output = FeatureTaskRuntimeBuildGateCoordinator.runtimeOwnedBuildOutput(
      repositoryCheckpoint = "checkpoint-fp",
      measurements = listOf(
        FeatureTaskRuntimeValidationGateRunRecord(
          durationMs = 12,
          outcome = "passed",
          cacheMode = "cache_eligible",
          executedWorkUnits = 1,
        ),
      ),
      checks = emptyList(),
    )
    val envelope = JsonSupport.parseObjectOrNull(output.payload)?.let(JsonSupport::jsonElementToValue)
      ?.let(JsonSupport::anyToStringAnyMap)
      ?: error("build gate output must parse as an object")
    val buildReceipt = JsonSupport.anyToStringAnyMap(
      JsonSupport.anyToStringAnyMap(envelope["produced_outputs"])?.get("build_receipt"),
    ) ?: error("build gate output must carry build_receipt")
    FeatureTaskRuntimeBuildReceiptSchemaValidator.validate(buildReceipt, sourceLabel = "build#1")
  }
}
