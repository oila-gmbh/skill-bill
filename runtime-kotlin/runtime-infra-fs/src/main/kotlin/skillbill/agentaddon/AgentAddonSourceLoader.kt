package skillbill.agentaddon

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.agentaddon.model.AgentAddonConsumer
import skillbill.agentaddon.model.AgentAddonDeclaration
import skillbill.error.MissingAgentAddonDeclarationError
import skillbill.install.model.InstallAgent
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.name

private const val AGENT_ADDONS_DIRECTORY = "agent-addons"
private const val MANIFEST_FILE = "agent-addon.yaml"
private const val CONTENT_FILE = "content.md"
private val allowedEntries = setOf(MANIFEST_FILE, CONTENT_FILE)

fun discoverAgentAddons(
  repoRoot: Path,
  schemaValidator: AgentAddonSchemaValidator = AgentAddonSchemaValidator(),
): List<AgentAddonDeclaration> {
  val rootLabel = repoRoot.resolve(AGENT_ADDONS_DIRECTORY).toString()
  return sourceOperation(rootLabel, "agent add-on root cannot be read") {
    val root = repoRoot.toAbsolutePath().normalize().resolve(AGENT_ADDONS_DIRECTORY)
    val rootAttributes = readRootAttributes(root) ?: return@sourceOperation emptyList()
    if (!rootAttributes.isDirectory) {
      invalid(rootLabel, "$AGENT_ADDONS_DIRECTORY root must be a directory")
    }
    val sourceDirectories = Files.list(root).use { stream ->
      stream.filter { !it.name.startsWith(".") }.sorted().toList()
    }
    val candidates = sourceDirectories.map { parseSource(it, schemaValidator) }
    validateSourceCoherence(root, candidates)
    candidates.sortedBy { it.slug }
  }
}

fun requireAgentAddon(repoRoot: Path, slug: String): AgentAddonDeclaration =
  discoverAgentAddons(repoRoot).firstOrNull { it.slug == slug }
    ?: throw MissingAgentAddonDeclarationError(slug, repoRoot.resolve(AGENT_ADDONS_DIRECTORY).toString())

private fun parseSource(sourceRoot: Path, validator: AgentAddonSchemaValidator): AgentAddonDeclaration {
  val manifest = sourceRoot.resolve(MANIFEST_FILE)
  val content = sourceRoot.resolve(CONTENT_FILE)
  val sourceLabel = manifest.toString()
  return sourceOperation(sourceLabel, "manifest cannot be parsed") {
    if (!Files.isDirectory(sourceRoot)) invalid(sourceLabel, "source entry must be a directory")
    requireRegularFile(manifest, sourceLabel)
    requireRegularFile(content, sourceLabel)
    val extras = unexpectedEntries(sourceRoot)
    if (extras.isNotEmpty()) {
      invalid(
        sourceLabel,
        "only $MANIFEST_FILE and $CONTENT_FILE are allowed; found ${extras.joinToString()}",
      )
    }
    val parsed: Any? = YAMLMapper().readValue(Files.readString(manifest), Any::class.java)
    if (parsed !is Map<*, *>) invalid(sourceLabel, "manifest must be a top-level mapping")
    @Suppress("UNCHECKED_CAST")
    val values = parsed as Map<String, Any?>
    validator.validate(values, sourceLabel)
    val slug = values.string("slug", sourceLabel)
    val description = values.string("description", sourceLabel)
    val violations = mutableListOf<String>()
    if (!description.isValidDescription()) {
      violations += "description must be non-blank, trimmed, and single-line"
    }
    val agentIds = values.stringList("agent_ids", sourceLabel)
    val agents = agentIds.map { id ->
      runCatching { InstallAgent.fromId(id) }.getOrElse {
        violations += "unknown agent id '$id'; supported: ${InstallAgent.supportedIds.joinToString()}"
        null
      }
    }.filterNotNull()
    val consumerIds = values.stringList("consumers", sourceLabel)
    val consumers = consumerIds.map { id ->
      runCatching { AgentAddonConsumer.fromId(id) }.getOrElse {
        violations += it.message ?: "unknown consumer '$id'"
        null
      }
    }.filterNotNull()
    if (violations.isNotEmpty()) invalid(sourceLabel, violations.joinToString("; "))
    AgentAddonDeclaration(
      contractVersion = values.string("contract_version", sourceLabel),
      slug = slug,
      description = description,
      agents = agents,
      consumers = consumers,
      addonRoot = sourceRoot,
      manifestPath = manifest,
      contentPath = content,
      canonicalSourceIdentity = manifest.toRealPath(),
    )
  }
}

private fun readRootAttributes(root: Path): BasicFileAttributes? = try {
  Files.readAttributes(root, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
} catch (_: NoSuchFileException) {
  null
}

private fun validateSourceCoherence(root: Path, candidates: List<AgentAddonDeclaration>) {
  val violations = mutableListOf<String>()
  candidates.filter { it.addonRoot.name != it.slug }.forEach { declaration ->
    violations += "${declaration.manifestPath}: source directory '${declaration.addonRoot.name}' " +
      "must match slug '${declaration.slug}'"
  }
  candidates.groupBy { it.slug }.filterValues { it.size > 1 }.forEach { (slug, declarations) ->
    violations += "duplicate slug '$slug' declared by " +
      declarations.joinToString { it.manifestPath.toString() }
  }
  candidates.groupBy { it.canonicalSourceIdentity }.filterValues { it.size > 1 }.forEach { (identity, _) ->
    violations += "duplicate canonical source identity '$identity'"
  }
  if (violations.isNotEmpty()) invalid(root.toString(), violations.sorted().joinToString("; "))
}

private fun unexpectedEntries(sourceRoot: Path): List<String> = Files.list(sourceRoot).use { stream ->
  stream.map { it.name }.filter { it !in allowedEntries }.sorted().toList()
}

private fun String.isValidDescription(): Boolean {
  if (isBlank() || this != trim()) return false
  return '\n' !in this && '\r' !in this
}

private fun requireRegularFile(path: Path, sourceLabel: String) {
  if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
    invalid(sourceLabel, "${path.name} must be a regular file")
  }
}
