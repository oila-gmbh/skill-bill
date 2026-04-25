package skillbill.cli

import skillbill.application.LearningDeleteResult
import skillbill.application.LearningListResult
import skillbill.application.LearningRecordResult
import skillbill.application.LearningResolveResult
import skillbill.contracts.learning.toLearningDeleteContract
import skillbill.contracts.learning.toLearningListContract
import skillbill.contracts.learning.toLearningRecordContract
import skillbill.contracts.learning.toLearningResolveContract

internal fun LearningListResult.toPayload(): Map<String, Any?> = toLearningListContract().toPayload()

internal fun LearningRecordResult.toPayload(): Map<String, Any?> = toLearningRecordContract().toPayload()

internal fun LearningResolveResult.toPayload(): Map<String, Any?> = toLearningResolveContract().toPayload()

internal fun LearningDeleteResult.toPayload(): Map<String, Any?> = toLearningDeleteContract().toPayload()
