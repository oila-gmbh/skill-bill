package skillbill.mcp.review

import skillbill.SkillBillVersion
import skillbill.ports.review.model.GovernedReviewEvidenceCodec

internal fun initializeResult(): Map<String, Any?> = linkedMapOf(
  "protocolVersion" to "2025-11-25",
  "capabilities" to mapOf("tools" to mapOf("listChanged" to false)),
  "serverInfo" to mapOf(
    "name" to GovernedReviewEvidenceCodec.SERVER_NAME,
    "version" to SkillBillVersion.VALUE,
  ),
)
