package skillbill.cli

import skillbill.application.LearningDeleteResult
import skillbill.application.LearningListResult
import skillbill.application.LearningRecordResult
import skillbill.application.LearningResolveResult
import skillbill.application.toLearningDeleteContract
import skillbill.application.toLearningListContract
import skillbill.application.toLearningRecordContract
import skillbill.application.toLearningResolveContract

internal fun LearningListResult.toPayload(): Map<String, Any?> = toLearningListContract().toPayload()

internal fun LearningRecordResult.toPayload(): Map<String, Any?> = toLearningRecordContract().toPayload()

internal fun LearningResolveResult.toPayload(): Map<String, Any?> = toLearningResolveContract().toPayload()

internal fun LearningDeleteResult.toPayload(): Map<String, Any?> = toLearningDeleteContract().toPayload()
