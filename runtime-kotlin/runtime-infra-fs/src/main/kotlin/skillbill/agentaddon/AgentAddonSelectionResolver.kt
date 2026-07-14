package skillbill.agentaddon

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.agentaddon.model.AgentAddonConsumer
import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.agentaddon.model.HydratedAgentAddonSelectionEntry
import skillbill.agentaddon.model.PersistedAgentAddonSelectionEntry
import skillbill.error.AgentAddonSelectionDriftError
import skillbill.error.InvalidAgentAddonSelectionError
import skillbill.install.model.InstallAgent
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class AgentAddonSelectionResolver {
  fun resolveInitial(
    repoRoot: Path,
    requestedSlugs: List<String>,
    consumer: AgentAddonConsumer,
    receivingAgentIds: List<String>,
  ): HydratedAgentAddonSelection {
    validateRequestedSlugs(requestedSlugs)
    val receivingAgents = receivingAgentIds.map(::parseAgent)
    val catalogue = discoverAgentAddons(repoRoot).associateBy { it.slug }
    return HydratedAgentAddonSelection(
      requestedSlugs.map { slug ->
        val declaration = catalogue[slug]
          ?: throw InvalidAgentAddonSelectionError("Unknown agent add-on '$slug'.")
        validateCompatibility(slug, declaration.consumers, declaration.agents, consumer, receivingAgents)
        hydrate(
          slug = slug,
          description = declaration.description,
          sourceIdentity = declaration.canonicalSourceIdentity,
          contentPath = declaration.contentPath,
        )
      },
    )
  }

  @Suppress("ThrowsCount") // Each identity or digest contract breach must retain its actionable typed failure.
  fun verifyPersisted(
    selection: AgentAddonSelection,
    consumer: AgentAddonConsumer,
    receivingAgentIds: List<String>,
  ): HydratedAgentAddonSelection {
    val receivingAgents = receivingAgentIds.map(::parseAgent)
    return HydratedAgentAddonSelection(
      selection.entries.map { recorded ->
        val manifest = Path.of(recorded.sourceIdentity)
        if (!Files.isRegularFile(manifest)) {
          throw InvalidAgentAddonSelectionError(
            "Selected agent add-on '${recorded.slug}' source is missing at '${recorded.sourceIdentity}'.",
          )
        }
        val values = YAMLMapper().readValue(Files.readAllBytes(manifest), Map::class.java)
        val slug = values["slug"] as? String
        if (slug != recorded.slug) {
          throw InvalidAgentAddonSelectionError(
            "Selected source '${recorded.sourceIdentity}' declares '$slug', expected '${recorded.slug}'.",
          )
        }
        val consumers = stringList(values, "consumers").map(AgentAddonConsumer::fromId)
        val agents = stringList(values, "agent_ids").map(InstallAgent::fromId)
        validateCompatibility(recorded.slug, consumers, agents, consumer, receivingAgents)
        val contentPath = manifest.resolveSibling("content.md")
        if (!Files.isRegularFile(contentPath)) {
          throw InvalidAgentAddonSelectionError("Selected agent add-on '${recorded.slug}' content.md is missing.")
        }
        val bytes = Files.readAllBytes(contentPath)
        if (sha256(bytes) != recorded.contentSha256) {
          throw AgentAddonSelectionDriftError(recorded.slug, recorded.sourceIdentity)
        }
        HydratedAgentAddonSelectionEntry(
          persisted = recorded,
          description = values["description"] as? String
            ?: throw InvalidAgentAddonSelectionError(
              "Selected agent add-on '${recorded.slug}' has no description.",
            ),
          content = bytes.toString(Charsets.UTF_8),
        )
      },
    )
  }

  private fun hydrate(
    slug: String,
    description: String,
    sourceIdentity: Path,
    contentPath: Path,
  ): HydratedAgentAddonSelectionEntry {
    val bytes = Files.readAllBytes(contentPath)
    return HydratedAgentAddonSelectionEntry(
      persisted = PersistedAgentAddonSelectionEntry(
        slug,
        sourceIdentity.toString(),
        sha256(bytes),
      ),
      description = description,
      content = bytes.toString(Charsets.UTF_8),
    )
  }

  private fun validateRequestedSlugs(slugs: List<String>) {
    val malformed = slugs.firstOrNull { !it.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")) }
    if (malformed != null) throw InvalidAgentAddonSelectionError("Malformed agent add-on slug '$malformed'.")
    val duplicate = slugs.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.key
    if (duplicate != null) {
      throw InvalidAgentAddonSelectionError(
        "Agent add-on '$duplicate' was selected more than once.",
      )
    }
  }

  private fun parseAgent(id: String): InstallAgent = runCatching { InstallAgent.fromId(id) }.getOrElse {
    throw InvalidAgentAddonSelectionError("Unknown receiving agent '$id'.")
  }

  private fun validateCompatibility(
    slug: String,
    consumers: List<AgentAddonConsumer>,
    agents: List<InstallAgent>,
    consumer: AgentAddonConsumer,
    receivingAgents: List<InstallAgent>,
  ) {
    if (consumer !in consumers) {
      throw InvalidAgentAddonSelectionError(
        "Agent add-on '$slug' does not support consumer '${consumer.id}'.",
      )
    }
    val incompatible = receivingAgents.firstOrNull { it !in agents }
    if (incompatible != null) {
      throw InvalidAgentAddonSelectionError(
        "Agent add-on '$slug' is incompatible with receiving agent '${incompatible.id}'.",
      )
    }
  }

  private fun stringList(values: Map<*, *>, key: String): List<String> =
    (values[key] as? List<*>)?.map { it as? String ?: invalidField(key) } ?: invalidField(key)

  private fun invalidField(key: String): Nothing =
    throw InvalidAgentAddonSelectionError("Selected agent add-on manifest field '$key' is malformed.")

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
