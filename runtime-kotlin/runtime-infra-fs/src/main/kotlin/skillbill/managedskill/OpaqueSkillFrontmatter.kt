package skillbill.managedskill

import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.events.AliasEvent
import org.yaml.snakeyaml.events.CollectionStartEvent
import org.yaml.snakeyaml.events.Event
import org.yaml.snakeyaml.events.NodeEvent
import org.yaml.snakeyaml.events.ScalarEvent
import org.yaml.snakeyaml.parser.ParserImpl
import org.yaml.snakeyaml.reader.StreamReader

internal fun parseFrontmatter(text: String): JsonNode {
  val lines = text.lineSequence().toList()
  if (lines.firstOrNull()?.trim() != "---") {
    invalidBundle("SKILL.md must start with YAML frontmatter.")
  }
  val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
  if (end < 0) invalidBundle("SKILL.md frontmatter is not terminated.")
  val yaml = lines.subList(1, end + 1).joinToString("\n")
  rejectYamlOwnershipSyntax(yaml)
  val node = bundleOperation("SKILL.md frontmatter is invalid YAML.") {
    YAMLMapper.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .build()
      .readTree(yaml)
  }
  requireOwnershipFields(node)
  return node
}

private fun requireOwnershipFields(node: JsonNode) {
  if (!node.isObject) invalidBundle("SKILL.md frontmatter must be a YAML mapping.")
  if (!node.path("name").isTextual || !node.path("description").isTextual) {
    invalidBundle("SKILL.md frontmatter name and description must be strings.")
  }
  if (containsNestedOwnershipName(node)) {
    invalidBundle("SKILL.md frontmatter must not contain a nested name key.")
  }
}

private fun rejectYamlOwnershipSyntax(yaml: String) {
  bundleOperation("SKILL.md frontmatter is invalid YAML.") {
    val parser = ParserImpl(StreamReader(yaml), LoaderOptions())
    while (parser.peekEvent() != null) {
      if (hasForbiddenYamlOwnership(parser.event)) {
        invalidBundle("SKILL.md frontmatter must not contain aliases, anchors, or custom tags.")
      }
    }
  }
}

private fun hasForbiddenYamlOwnership(event: Event): Boolean {
  if (event is AliasEvent) return true
  if (event !is NodeEvent) return false
  if (event.anchor != null) return true
  return when (event) {
    is ScalarEvent -> event.tag != null
    is CollectionStartEvent -> event.tag != null
    else -> false
  }
}

private fun containsNestedOwnershipName(root: JsonNode): Boolean {
  fun visit(node: JsonNode, depth: Int): Boolean = when {
    node.isObject -> node.fields().asSequence().any { (key, value) ->
      (depth > 0 && key == "name") || visit(value, depth + 1)
    }
    node.isArray -> node.elements().asSequence().any { visit(it, depth + 1) }
    else -> false
  }
  return root.fields().asSequence().any { (_, value) -> visit(value, 1) }
}
