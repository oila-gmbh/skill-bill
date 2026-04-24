package skillbill.mcp

import skillbill.application.LearningResolveResult
import skillbill.contracts.learning.toLearningResolveContract

internal fun LearningResolveResult.toMcpPayload(): Map<String, Any?> = toLearningResolveContract().toPayload()
