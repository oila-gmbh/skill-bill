package skillbill.desktop.core.data.service

import skillbill.desktop.core.data.di.DesktopRuntimeApplicationServices
import skillbill.desktop.core.data.service.mapper.authoredSkillEntries
import skillbill.desktop.core.domain.model.GeneratedArtifactDetail
import skillbill.desktop.core.domain.model.SkillBillTreeItem
import skillbill.desktop.core.domain.model.SkillBillTreeItemMetadata
import skillbill.desktop.core.domain.model.TreeItemKind
import skillbill.install.model.ExternalAddonSource
import skillbill.ports.scaffold.model.NativeAgentSourceProjection
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal class SkillTreeBuilder(
  private val runtimeServices: DesktopRuntimeApplicationServices,
) {
  private val scaffoldService get() = runtimeServices.scaffoldService
  private val repoSourceDiscoveryService get() = runtimeServices.repoSourceDiscoveryService

  fun buildTree(
    root: Path,
    baselineModifiedResolver: (Path) -> Set<String>,
    externalAddonSourcesResolver: () -> List<ExternalAddonSource>,
    skillBillConfigPathResolver: () -> Path,
  ): TreeBuildResult {
    val repoToken = repoToken(root)
    val selections = linkedMapOf<String, SelectionDetail>()
    val authoredSkills = loadAuthoredSkills(root, repoToken, selections, baselineModifiedResolver)
    val configuration = listOf(loadConfiguration(root, repoToken, selections, skillBillConfigPathResolver))
    val addons = loadAddons(root, repoToken, selections, externalAddonSourcesResolver)
    val agentAddons = loadAgentAddons(root, repoToken, selections)
    val nativeAgents = loadNativeAgents(root, repoToken, selections)
    val generatedArtifacts = loadGeneratedArtifacts(root, repoToken, selections)

    val groups = listOfNotNull(
      group(selectionId(repoToken, "horizontal-skills"), "Horizontal Skills", authoredSkills.horizontal),
      group(selectionId(repoToken, "platform-pack-skills"), "Platform Packs", authoredSkills.platform),
      group(selectionId(repoToken, "configuration"), "Configuration", configuration),
      group(selectionId(repoToken, "addons"), "Add-ons", addons),
      group(selectionId(repoToken, "agent-addons"), "Agent Add-ons", agentAddons),
      group(selectionId(repoToken, "native-agents"), "Native Agents", nativeAgents),
      group(selectionId(repoToken, "generated-artifacts"), "Generated Artifacts", generatedArtifacts),
    )
    return TreeBuildResult(items = groups, selections = selections)
  }

  private fun loadAgentAddons(
    root: Path,
    repoToken: String,
    selections: MutableMap<String, SelectionDetail>,
  ): List<SkillBillTreeItem> = repoSourceDiscoveryService.discoverAgentAddons(root).map { addon ->
    val id = selectionId(repoToken, addon.identity)
    val authoredPath = relativePath(root, addon.contentPath)
    val metadata = SkillBillTreeItemMetadata(
      kind = "agent-addon",
      description = addon.description,
      supportedAgents = addon.agentIds,
      consumers = addon.consumers,
    )
    selections[id] = SelectionDetail(
      repoToken = repoToken,
      title = addon.slug,
      detail = addon.description,
      kind = "agent-addon",
      authoredPath = authoredPath,
      status = "authored",
      contentFile = addon.contentPath,
      editable = true,
      metadata = metadata,
    )
    SkillBillTreeItem(
      id = id,
      label = addon.slug,
      kind = TreeItemKind.AGENT_ADDON,
      authoredPath = authoredPath,
      status = "authored",
      editable = true,
      metadata = metadata,
    )
  }.sortedBy { item -> item.label }

  private fun loadConfiguration(
    root: Path,
    repoToken: String,
    selections: MutableMap<String, SelectionDetail>,
    skillBillConfigPathResolver: () -> Path,
  ): SkillBillTreeItem {
    val configPath = skillBillConfigPathResolver()
    val authoredPath = relativePath(root, configPath)
    val metadata = SkillBillTreeItemMetadata(kind = SKILL_BILL_CONFIG_KIND)
    val id = selectionId(repoToken, "config:skill-bill")
    selections[id] =
      SelectionDetail(
        repoToken = repoToken,
        title = "Skill Bill config",
        detail = "Machine Skill Bill config used for external add-on sources and telemetry.",
        kind = SKILL_BILL_CONFIG_KIND,
        authoredPath = authoredPath,
        status = "config",
        contentFile = configPath,
        editable = true,
        metadata = metadata,
      )
    return SkillBillTreeItem(
      id = id,
      label = "Skill Bill config",
      kind = TreeItemKind.CONFIG,
      authoredPath = authoredPath,
      status = "config",
      editable = true,
      metadata = metadata,
    )
  }

  private fun loadAuthoredSkills(
    root: Path,
    repoToken: String,
    selections: MutableMap<String, SelectionDetail>,
    baselineModifiedResolver: (Path) -> Set<String>,
  ): AuthoredSkillGroups {
    val entries = scaffoldService.list(root, emptyList()).authoredSkillEntries()
    val modifiedSkillPaths = baselineModifiedResolver(root)
    val horizontal = mutableListOf<SkillBillTreeItem>()
    val platformChildren = linkedMapOf<String, MutableList<SkillBillTreeItem>>()
    entries.forEach { entry ->
      val skillName = entry.skillName
      val platform = entry.platform
      val family = entry.family
      val area = entry.area
      val contentFile = entry.contentFile
      val status = entry.completionStatus
      val kind = if (platform.isBlank()) "horizontal skill" else "platform pack skill"
      val id = selectionId(repoToken, "skill:$skillName")
      val metadata =
        SkillBillTreeItemMetadata(
          skillName = skillName,
          kind = kind,
          packageName = entry.packageName,
          platform = platform.takeIf(String::isNotBlank),
          family = family.takeIf(String::isNotBlank),
          area = area.takeIf(String::isNotBlank),
        )
      val item =
        SkillBillTreeItem(
          id = id,
          label = SkillTreePresenter.displayLabelForSkill(
            skillName = skillName,
            platform = platform,
            family = family,
            area = area,
          ),
          kind = SkillItemKind,
          authoredPath = relativePath(root, contentFile),
          status = status,
          editable = true,
          metadata = metadata,
          baselineModified = skillRelativeKey(root, contentFile) in modifiedSkillPaths,
        )
      selections[id] =
        SelectionDetail(
          repoToken = repoToken,
          title = skillName,
          detail = "Authored Skill Bill source.",
          skillName = skillName,
          kind = kind,
          authoredPath = relativePath(root, contentFile),
          status = status,
          editable = true,
          contentFile = Path.of(contentFile),
          generatedArtifacts = generatedArtifactsForSkill(root, Path.of(contentFile)),
          metadata = metadata,
        )
      if (platform.isBlank()) {
        horizontal += item
      } else {
        platformChildren.getOrPut(platform) { mutableListOf() } += item
      }
    }
    val platform =
      platformChildren.map { (platform, children) ->
        SkillBillTreeItem(
          id = selectionId(repoToken, "platform:$platform"),
          label = platform,
          kind = TreeItemKind.PLATFORM_PACK,
          editable = false,
          children = children.sortedBy { it.label },
        )
      }
    return AuthoredSkillGroups(horizontal = horizontal.sortedBy { it.label }, platform = platform.sortedBy { it.label })
  }

  private fun loadAddons(
    root: Path,
    repoToken: String,
    selections: MutableMap<String, SelectionDetail>,
    externalAddonSourcesResolver: () -> List<ExternalAddonSource>,
  ): List<SkillBillTreeItem> {
    val addonsByPlatform = linkedMapOf<String, MutableList<SkillBillTreeItem>>()
    val externalAddonKeys = linkedSetOf<AddonKey>()
    runCatching { externalAddonSourcesResolver() }.getOrDefault(emptyList()).forEach { source ->
      source.topLevelMarkdownFiles().forEach { addon ->
        val platform = source.platform
        val slug = addon.fileName.toString().removeSuffix(".md")
        val key = AddonKey(platform, slug)
        if (externalAddonKeys.add(key)) {
          val authoredPath = relativePath(root, addon)
          val externalSourcePath = source.stableSourcePath()
          val id = selectionId(repoToken, "addon-external:$platform:${addon.stableSourcePath()}")
          val metadata = SkillBillTreeItemMetadata(
            kind = "add-on",
            platform = platform,
            externalSourcePath = externalSourcePath,
          )
          selections[id] =
            SelectionDetail(
              repoToken = repoToken,
              title = addon.fileName.toString(),
              detail = "External add-on source from ${source.path}.",
              kind = "add-on",
              authoredPath = authoredPath,
              status = "authored",
              contentFile = addon,
              editable = true,
              metadata = metadata,
            )
          addonsByPlatform.getOrPut(platform) { mutableListOf() } += SkillBillTreeItem(
            id = id,
            label = slug,
            kind = TreeItemKind.ADD_ON,
            authoredPath = authoredPath,
            status = "authored",
            editable = true,
            external = true,
            metadata = metadata,
          )
        }
      }
    }
    repoSourceDiscoveryService.discoverGovernedAddonFiles(root)
      .forEach { addonFile ->
        if (AddonKey(addonFile.packSlug, addonFile.addonSlug) in externalAddonKeys) {
          return@forEach
        }
        val addon = addonFile.addonPath
        val relative = relativePath(root, addon)
        val id = selectionId(repoToken, "addon:$relative")
        selections[id] =
          SelectionDetail(
            repoToken = repoToken,
            title = addon.fileName.toString(),
            detail = "Pack-owned add-on source.",
            kind = "add-on",
            authoredPath = relative,
            status = "authored",
            contentFile = addon,
            editable = true,
          )
        addonsByPlatform.getOrPut(addonFile.packSlug) { mutableListOf() } += SkillBillTreeItem(
          id = id,
          label = addonFile.addonSlug,
          kind = TreeItemKind.ADD_ON,
          authoredPath = relative,
          status = "authored",
          editable = true,
          metadata = SkillBillTreeItemMetadata(kind = "add-on", platform = addonFile.packSlug),
        )
      }
    return addonsByPlatform.map { (platform, addons) ->
      SkillBillTreeItem(
        id = selectionId(repoToken, "addon-platform:$platform"),
        label = platform,
        kind = TreeItemKind.GROUP,
        editable = false,
        children = addons.sortedBy { item -> item.label },
        metadata = SkillBillTreeItemMetadata(kind = "add-on group", platform = platform),
      )
    }.sortedBy { item -> item.label }
  }

  private fun loadNativeAgents(
    root: Path,
    repoToken: String,
    selections: MutableMap<String, SelectionDetail>,
  ): List<SkillBillTreeItem> {
    val leaves = repoSourceDiscoveryService.discoverNativeAgentSourceFiles(
      platformPacksRoot = root.resolve("platform-packs"),
      skillsRoot = root.resolve("skills"),
    )
      .flatMap { sourceFile ->
        val relative = relativePath(root, sourceFile)
        runCatching { repoSourceDiscoveryService.parseNativeAgentSourceFile(sourceFile) }
          .fold(
            onSuccess = { agents ->
              agents.map { agent ->
                val id = selectionId(repoToken, "native-agent:$relative:${agent.bundleEntryName ?: agent.name}")
                val groupPath = SkillTreePresenter.nativeAgentOwnerGroupPath(relative)
                selections[id] =
                  SelectionDetail(
                    repoToken = repoToken,
                    title = agent.name,
                    detail = "Provider-neutral native-agent source.",
                    kind = "native agent",
                    authoredPath = relative,
                    status = agent.compositionKindWireValue ?: "authored",
                    contentFile = sourceFile,
                    contentOverride = renderDisplayNativeAgentSource(root, agent),
                    editable = false,
                    readOnlyLabel = READ_ONLY_LABEL,
                    readOnlyReason = AUTHORED_SOURCE_READ_ONLY_REASON,
                  )
                NativeAgentTreeLeaf(
                  groupPath = groupPath,
                  item = SkillBillTreeItem(
                    id = id,
                    label = SkillTreePresenter.displayLabelForNativeAgent(agent.name, relative, groupPath),
                    kind = TreeItemKind.NATIVE_AGENT,
                    authoredPath = relative,
                    status = agent.compositionKindWireValue ?: "authored",
                    editable = false,
                    readOnlyLabel = READ_ONLY_LABEL,
                    metadata = SkillBillTreeItemMetadata(kind = "native agent"),
                  ),
                )
              }
            },
            onFailure = { error ->
              val id = selectionId(repoToken, "native-agent-error:$relative")
              val message = error.message.orEmpty().ifBlank { "Native-agent source could not be parsed." }
              val groupPath = SkillTreePresenter.nativeAgentOwnerGroupPath(relative)
              selections[id] =
                SelectionDetail(
                  repoToken = repoToken,
                  title = sourceFile.fileName.toString(),
                  detail = "Invalid native-agent source: $message",
                  kind = "native agent",
                  authoredPath = relative,
                  status = "invalid",
                  contentFile = sourceFile,
                  editable = false,
                  readOnlyLabel = READ_ONLY_LABEL,
                  readOnlyReason = AUTHORED_SOURCE_READ_ONLY_REASON,
                )
              listOf(
                NativeAgentTreeLeaf(
                  groupPath = groupPath,
                  item = SkillBillTreeItem(
                    id = id,
                    label = "${SkillTreePresenter.displayLabelForNativeAgent(
                      sourceFile.fileName.toString().removeSuffix(".md"),
                      relative,
                      groupPath,
                    )} (invalid)",
                    kind = TreeItemKind.NATIVE_AGENT,
                    authoredPath = relative,
                    status = "invalid",
                    editable = false,
                    readOnlyLabel = READ_ONLY_LABEL,
                    metadata = SkillBillTreeItemMetadata(kind = "native agent"),
                  ),
                ),
              )
            },
          )
      }
    return groupNativeAgentLeaves(repoToken, leaves)
  }

  private fun renderDisplayNativeAgentSource(root: Path, agent: NativeAgentSourceProjection): String =
    runCatching { repoSourceDiscoveryService.renderComposedNativeAgentSource(root, agent) }
      .getOrDefault(repoSourceDiscoveryService.renderNativeAgentSource(agent))

  private fun groupNativeAgentLeaves(repoToken: String, leaves: List<NativeAgentTreeLeaf>): List<SkillBillTreeItem> {
    val root = MutableNativeAgentGroup(label = "", path = emptyList())
    leaves.forEach { leaf ->
      leaf.groupPath.fold(root) { group, label ->
        group.groups.getOrPut(label) { MutableNativeAgentGroup(label = label, path = group.path + label) }
      }.items += leaf.item
    }
    return root.children(repoToken)
  }

  private fun MutableNativeAgentGroup.children(repoToken: String): List<SkillBillTreeItem> = groups.values
    .sortedBy { group -> group.label }
    .map { group -> group.toTreeItem(repoToken) } +
    items.sortedBy { item -> item.label }

  private fun MutableNativeAgentGroup.toTreeItem(repoToken: String): SkillBillTreeItem = SkillBillTreeItem(
    id = selectionId(repoToken, "native-agent-group:${path.joinToString("/")}"),
    label = label,
    kind = TreeItemKind.GROUP,
    editable = false,
    children = children(repoToken),
    metadata = SkillBillTreeItemMetadata(kind = "native agent group"),
  )

  private fun loadGeneratedArtifacts(
    root: Path,
    repoToken: String,
    selections: MutableMap<String, SelectionDetail>,
  ): List<SkillBillTreeItem> {
    return repoSourceDiscoveryService.discoverGeneratedArtifactFiles(root).map { generated ->
      val artifact = generated.path
      val relative = relativePath(root, artifact)
      val id = selectionId(repoToken, "generated:$relative")
      selections[id] =
        SelectionDetail(
          repoToken = repoToken,
          title = artifact.fileName.toString(),
          detail = generated.reason,
          kind = "generated artifact",
          authoredPath = relative,
          status = "read-only",
          editable = false,
          readOnlyLabel = READ_ONLY_LABEL,
          readOnlyReason = generated.reason,
          contentFile = artifact,
        )
      SkillBillTreeItem(
        id = id,
        label = relative,
        kind = TreeItemKind.GENERATED_ARTIFACT,
        authoredPath = relative,
        status = "read-only",
        editable = false,
        readOnlyLabel = READ_ONLY_LABEL,
        metadata = SkillBillTreeItemMetadata(kind = "generated artifact"),
      )
    }
  }

  private fun generatedArtifactsForSkill(root: Path, contentFile: Path): List<GeneratedArtifactDetail> {
    val skillDir = contentFile.parent ?: return emptyList()
    val normalizedSkillDir = skillDir.toAbsolutePath().normalize()
    return repoSourceDiscoveryService.discoverGeneratedArtifactFiles(root)
      .filter { artifact -> artifact.path.toAbsolutePath().normalize().parent == normalizedSkillDir }
      .map { artifact ->
        GeneratedArtifactDetail(
          path = relativePath(root, artifact.path),
          reason = artifact.reason,
        )
      }
  }
}

private data class AddonKey(
  val platform: String,
  val slug: String,
)

private fun ExternalAddonSource.topLevelMarkdownFiles(): List<Path> = runCatching {
  Files.list(path).use { paths ->
    paths
      .filter { file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) }
      .filter { file -> file.fileName.toString().endsWith(".md") }
      .sorted(Comparator { left, right -> left.fileName.toString().compareTo(right.fileName.toString()) })
      .toList()
  }
}.getOrDefault(emptyList())

private fun Path.stableSourcePath(): String = toAbsolutePath().normalize().toString().replace('\\', '/')

private fun ExternalAddonSource.stableSourcePath(): String = path.stableSourcePath()

private data class NativeAgentTreeLeaf(
  val groupPath: List<String>,
  val item: SkillBillTreeItem,
)

private data class MutableNativeAgentGroup(
  val label: String,
  val path: List<String>,
  val groups: MutableMap<String, MutableNativeAgentGroup> = linkedMapOf(),
  val items: MutableList<SkillBillTreeItem> = mutableListOf(),
)

internal data class AuthoredSkillGroups(
  val horizontal: List<SkillBillTreeItem>,
  val platform: List<SkillBillTreeItem>,
)
