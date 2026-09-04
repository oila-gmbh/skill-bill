package skillbill.mcp.core

import skillbill.mcp.review.GovernedReviewEvidenceBridge
import skillbill.mcp.shared.McpRuntimeContext

fun main() {
  val environment = System.getenv()
  if (GovernedReviewEvidenceBridge.enabled(environment)) {
    GovernedReviewEvidenceBridge.run(environment)
  } else {
    McpStdioServer.run(McpRuntimeContext(environment = environment))
  }
}
