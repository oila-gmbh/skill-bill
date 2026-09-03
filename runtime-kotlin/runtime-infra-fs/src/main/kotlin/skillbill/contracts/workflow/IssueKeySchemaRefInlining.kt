package skillbill.contracts.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.issuekey.ISSUE_KEY_SCHEMA_ID
import skillbill.contracts.issuekey.ISSUE_KEY_SCHEMA_RESOURCE

internal fun JsonNode.inlineIssueKeySchemaRefs(): JsonNode {
  inlineIssueKeySchemaRefsIn(this, issueKeySchemaBody())
  return this
}

private fun issueKeySchemaBody(): JsonNode {
  val stream = checkNotNull(
    Thread.currentThread().contextClassLoader.getResourceAsStream(ISSUE_KEY_SCHEMA_RESOURCE)
      ?: IssueKeyShapeClasspath::class.java.classLoader.getResourceAsStream(ISSUE_KEY_SCHEMA_RESOURCE),
  ) { "issue-key schema is missing from the classpath at $ISSUE_KEY_SCHEMA_RESOURCE" }
  val raw = stream.use { YAMLMapper().readTree(it) }
  if (raw !is ObjectNode) {
    throw IllegalStateException("issue-key schema must be an object")
  }
  raw.remove("\$schema")
  raw.remove("\$id")
  raw.remove("title")
  return raw
}

private fun inlineIssueKeySchemaRefsIn(node: JsonNode, body: JsonNode) {
  if (node is ObjectNode) {
    val ref = node.get("\$ref")?.asText()
    if (ref == ISSUE_KEY_SCHEMA_ID) {
      node.remove("\$ref")
      body.fields().forEachRemaining { field ->
        node.replace(field.key, field.value.deepCopy())
      }
      return
    }
    node.fields().forEachRemaining { field -> inlineIssueKeySchemaRefsIn(field.value, body) }
    return
  }
  if (node.isArray) {
    node.forEach { child -> inlineIssueKeySchemaRefsIn(child, body) }
  }
}

private object IssueKeyShapeClasspath
