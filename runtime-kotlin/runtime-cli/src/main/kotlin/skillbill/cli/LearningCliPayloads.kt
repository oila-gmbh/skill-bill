package skillbill.cli

import skillbill.application.model.LearningDeleteResult
import skillbill.application.model.LearningListResult
import skillbill.application.model.LearningRecordResult
import skillbill.application.model.LearningResolveResult
import skillbill.application.toLearningDeleteContract
import skillbill.application.toLearningListContract
import skillbill.application.toLearningRecordContract
import skillbill.application.toLearningResolveContract

internal fun LearningListResult.toPayload(): Map<String, Any?> = toLearningListContract().toPayload()

internal fun LearningRecordResult.toPayload(): Map<String, Any?> = toLearningRecordContract().toPayload()

internal fun LearningResolveResult.toPayload(): Map<String, Any?> = toLearningResolveContract().toPayload()

internal fun LearningDeleteResult.toPayload(): Map<String, Any?> = toLearningDeleteContract().toPayload()
