package skillbill.cli.learning

import skillbill.application.learning.toLearningDeleteContract
import skillbill.application.learning.toLearningListContract
import skillbill.application.learning.toLearningRecordContract
import skillbill.application.learning.toLearningResolveContract
import skillbill.application.model.LearningDeleteResult
import skillbill.application.model.LearningListResult
import skillbill.application.model.LearningRecordResult
import skillbill.application.model.LearningResolveResult

internal fun LearningListResult.toPayload(): Map<String, Any?> = toLearningListContract().toPayload()

internal fun LearningRecordResult.toPayload(): Map<String, Any?> = toLearningRecordContract().toPayload()

internal fun LearningResolveResult.toPayload(): Map<String, Any?> = toLearningResolveContract().toPayload()

internal fun LearningDeleteResult.toPayload(): Map<String, Any?> = toLearningDeleteContract().toPayload()
