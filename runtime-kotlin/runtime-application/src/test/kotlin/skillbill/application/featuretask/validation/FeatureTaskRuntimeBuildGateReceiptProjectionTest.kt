package skillbill.application.featuretask.validation

import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FeatureTaskRuntimeBuildReceiptSchemaValidator
import kotlin.test.Test

class FeatureTaskRuntimeBuildGateReceiptProjectionTest {
  @Test
  fun `settled build gate coordinator output validates against build_receipt contract`() {
    val output = FeatureTaskRuntimeBuildGateCoordinator.runtimeOwnedBuildOutput()
    val envelope = JsonSupport.parseObjectOrNull(output.payload)?.let(JsonSupport::jsonElementToValue)
      ?.let(JsonSupport::anyToStringAnyMap)
      ?: error("build gate output must parse as an object")
    val buildReceipt = JsonSupport.anyToStringAnyMap(
      JsonSupport.anyToStringAnyMap(envelope["produced_outputs"])?.get("build_receipt"),
    ) ?: error("build gate output must carry build_receipt")
    FeatureTaskRuntimeBuildReceiptSchemaValidator.validate(buildReceipt, sourceLabel = "build#1")
  }
}
