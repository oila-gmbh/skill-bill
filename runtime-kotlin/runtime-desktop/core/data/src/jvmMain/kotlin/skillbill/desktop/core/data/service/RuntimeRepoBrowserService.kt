package skillbill.desktop.core.data.service

import me.tatarka.inject.annotations.Inject
import skillbill.desktop.core.common.di.UserScope
import skillbill.desktop.core.data.di.DesktopRuntimeApplicationServices
import skillbill.desktop.core.data.service.mapper.authoredSkillEntries
import skillbill.desktop.core.domain.model.AuthoredContentDocument
import skillbill.desktop.core.domain.model.AuthoringSaveResult
import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.GeneratedArtifactDetail
import skillbill.desktop.core.domain.model.RepoLoadState
import skillbill.desktop.core.domain.model.RepoLoadStatus
import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.model.SkillBillTreeItem
import skillbill.desktop.core.domain.model.SkillBillTreeItemMetadata
import skillbill.desktop.core.domain.model.TreeItemKind
import skillbill.desktop.core.domain.service.AuthoringGateway
import skillbill.desktop.core.domain.service.InstalledWorkspaceBaselineService
import skillbill.desktop.core.domain.service.RepoSessionService
import skillbill.desktop.core.domain.service.SkillTreeService
import skillbill.error.SkillBillRuntimeException
import skillbill.ports.scaffold.model.NativeAgentSourceProjection
import skillbill.ports.scaffold.source.model.ScaffoldSaveExactContentResult
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.relativeTo

@Inject
@SingleIn(UserScope::class)
class RuntimeRepoBrowserService(
  private val runtimeServices: DesktopRuntimeApplicationServices =
    DesktopRuntimeApplicationServices.forCurrentUserHome(),
) :
  RepoSessionService,
  SkillTreeService,
  AuthoringGateway {
  private val scaffoldService get() = runtimeServices.scaffoldService
  private val repoSourceDiscoveryService get() = runtimeServices.repoSourceDiscoveryService
  private val repoValidationService get() = runtimeServices.repoValidationService

  // SKILL-52.2 subtask 5: returns the typed `ScaffoldSaveExactContentResult`
  // instead of reaching into its `.payload` raw map. The actual return value is
  // unused at the call site (the success/failure signal is the absence of an
  // exception); the typed receiver lets tests still swap in throwing or
  // succeeding fakes without re-introducing raw-map indexing in service code.
  internal var authoringSaver: (Path, String, String) -> ScaffoldSaveExactContentResult = { root, skillName, body ->
    scaffoldService.saveExactContent(root, skillName, body)
  }
  internal var sourceFileSaver: (Path, String) -> Unit = { sourceFile, body ->
    Files.writeString(sourceFile, body)
  }

  private val installedWorkspaceBaselineService: InstalledWorkspaceBaselineService by lazy {
    JvmInstalledWorkspaceBaselineService(JvmInstalledWorkspaceLocator(), runtimeServices)
  }

  // SKILL-77 subtask 4: per-skill locally-modified-vs-baseline paths for installed-workspace
  // sessions only. Returns empty for clone sessions and an absent baseline manifest. Tests
  // swap this seam to drive the decoration without a real installed workspace on disk.
  internal var baselineModifiedResolver: (Path) -> Set<String> = { root ->
    installedWorkspaceBaselineService.modifiedSkillRelativePaths(root)
  }

  private var snapshot: RepoBrowserSnapshot = RepoBrowserSnapshot.empty

  override fun open(repoPath: String): RepoSession {
    val rawRepoPath = repoPath.trim()
    val root =
      resolveRepoPath(repoPath)
        ?: return invalidSession(rawRepoPath, "Select a Skill Bill repository path.").also { session ->
          snapshot = RepoBrowserSnapshot(loadStatus = session.loadStatus)
        }
    val status = validateRoot(root)
    if (status.state != RepoLoadState.LOADED) {
      snapshot = RepoBrowserSnapshot(repoRoot = root, loadStatus = status)
      return RepoSession(
        repoPath = root.toString(),
        isRecognizedSkillBillRepo = false,
        loadStatus = status,
      )
    }

    val tree =
      runCatching { buildTree(root) }
        .getOrElse { error ->
          val invalidStatus =
            status.copy(
              state = RepoLoadState.INVALID,
              message = "Could not load Skill Bill repo tree: ${error.message.orEmpty()}",
            )
          snapshot = RepoBrowserSnapshot(repoRoot = root, loadStatus = invalidStatus)
          return RepoSession(
            repoPath = root.toString(),
            isRecognizedSkillBillRepo = false,
            loadStatus = invalidStatus,
          )
        }
    snapshot =
      RepoBrowserSnapshot(
        repoRoot = root,
        repoToken = repoToken(root),
        loadStatus = status,
        treeItems = tree.items,
        selections = tree.selections,
      )
    return RepoSession(
      repoPath = root.toString(),
      isRecognizedSkillBillRepo = true,
      loadStatus = status,
    )
  }

  override fun treeFor(session: RepoSession?): List<SkillBillTreeItem> =
    if (session?.isRecognizedSkillBillRepo == true && snapshot.repoRoot?.toString() == session.repoPath) {
      snapshot.treeItems
    } else {
      emptyList()
    }

  override fun describeSelection(treeItemId: String): EditorPlaceholder = snapshot.selections[treeItemId]
    ?.takeIf { detail -> detail.repoToken == snapshot.repoToken }
    ?.toEditorPlaceholder()
    ?: EditorPlaceholder.empty

  override fun loadDocument(session: RepoSession?, treeItemId: String?): AuthoredContentDocument {
    val detail = selectionFor(session, treeItemId)
      ?: return AuthoredContentDocument(
        treeItemId = treeItemId,
        title = "No source selected",
        skillName = null,
        kind = null,
        authoredPath = null,
        text = "",
        editable = false,
        readOnlyReason = "No editable governed source is selected.",
      )
    return detail.toDocument(treeItemId)
  }

  override fun saveDocument(session: RepoSession?, treeItemId: String?, body: String): AuthoringSaveResult {
    val root = snapshot.repoRoot
      ?: return AuthoringSaveResult.failed("No Skill Bill repository is open.")
    if (session?.isRecognizedSkillBillRepo != true || session.repoPath != root.toString()) {
      return AuthoringSaveResult.failed("No editable Skill Bill repository is open.")
    }
    val detail = selectionFor(session, treeItemId)
      ?: return AuthoringSaveResult.failed("No editable governed source is selected.")
    if (!detail.canEdit()) {
      return AuthoringSaveResult.failed(detail.readOnlyReasonForDocument())
    }
    return runCatching {
      val skillName = detail.skillName
      if (skillName != null) {
        authoringSaver(root, skillName, body)
      } else if (detail.kind == "add-on") {
        sourceFileSaver(detail.contentFile ?: error("Editable add-on selection is missing a source file."), body)
      } else {
        error("Only governed content.md files and add-ons can be saved through authoring.")
      }
      AuthoringSaveResult(
        success = true,
        document = detail.toDocument(treeItemId),
      )
    }.getOrElse { error ->
      AuthoringSaveResult.failed(runtimeMessage(error))
    }
  }

  override fun resolveGeneratedArtifactTreeItemId(session: RepoSession?, artifactPath: String): String? {
    if (session?.isRecognizedSkillBillRepo != true) {
      return null
    }
    val capturedSnapshot = snapshot
    if (capturedSnapshot.repoRoot?.toString() != session.repoPath) {
      return null
    }
    val root = capturedSnapshot.repoRoot
    val normalized = normalizeSourcePath(root, artifactPath)
    if (normalized.isBlank()) {
      return null
    }
    return capturedSnapshot.selections.entries
      .firstOrNull { (_, detail) ->
        detail.repoToken == capturedSnapshot.repoToken &&
          detail.kind == "generated artifact" &&
          detail.authoredPath?.replace('\\', '/') == normalized
      }
      ?.key
  }

  private fun validateRoot(root: Path): RepoLoadStatus {
    if (!Files.isDirectory(root)) {
      return RepoLoadStatus(
        state = RepoLoadState.INVALID,
        message = "Selected path is not a directory: $root",
      )
    }
    if (!looksLikeSkillBillRepo(root)) {
      return RepoLoadStatus(
        state = RepoLoadState.INVALID,
        message = "Selected directory is not a Skill Bill repo. Expected skills/ or platform-packs/ sources.",
      )
    }

    return runCatching { repoValidationService.validateRepo(root) }
      .fold(
        onSuccess = { report ->
          RepoLoadStatus(
            state = RepoLoadState.LOADED,
            message =
            if (report.passed) {
              "Skill Bill repo loaded."
            } else {
              "Skill Bill repo loaded with validation issues."
            },
            issueCount = report.issues.size,
            skillCount = report.skillCount,
            addonCount = report.addonCount,
            platformPackCount = report.platformPackCount,
            nativeAgentCount = report.nativeAgentCount,
            issues = report.issues,
          )
        },
        onFailure = { error ->
          RepoLoadStatus(
            state = RepoLoadState.INVALID,
            message = "Could not validate Skill Bill repo: ${error.message.orEmpty()}",
          )
        },
      )
  }

  private fun buildTree(root: Path): TreeBuildResult {
    val repoToken = repoToken(root)
    val selections = linkedMapOf<String, SelectionDetail>()
    val authoredSkills = loadAuthoredSkills(root, repoToken, selections)
    val addons = loadAddons(root, repoToken, selections)
    val nativeAgents = loadNativeAgents(root, repoToken, selections)
    val generatedArtifacts = loadGeneratedArtifacts(root, repoToken, selections)

    val groups = listOfNotNull(
      group(selectionId(repoToken, "horizontal-skills"), "Horizontal Skills", authoredSkills.horizontal),
      group(selectionId(repoToken, "platform-pack-skills"), "Platform Packs", authoredSkills.platform),
      group(selectionId(repoToken, "addons"), "Add-ons", addons),
      group(selectionId(repoToken, "native-agents"), "Native Agents", nativeAgents),
      group(selectionId(repoToken, "generated-artifacts"), "Generated Artifacts", generatedArtifacts),
    )
    return TreeBuildResult(items = groups, selections = selections)
  }

  private fun loadAuthoredSkills(
    root: Path,
    repoToken: String,
    selections: MutableMap<String, SelectionDetail>,
  ): AuthoredSkillGroups {
    // SKILL-52.3 subtask 3: typed authored-skill entries from the mapper. The
    // `ScaffoldListResult.payload` open boundary was retired; the result now carries a
    // typed `List<ScaffoldSkillStatus>` and `mapper/ScaffoldListResultMapper.kt` is a
    // 1:1 typed projection with no raw-map indexing.
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
          label = displayLabelForSkill(skillName = skillName, platform = platform, family = family, area = area),
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

  private fun displayLabelForSkill(skillName: String, platform: String, family: String, area: String): String {
    if (platform.isBlank()) {
      return skillName.removePrefix("bill-")
    }
    if (area.isNotBlank()) {
      return area
    }
    if (family.isNotBlank()) {
      return family
    }
    return skillName
      .removePrefix("bill-$platform-")
      .removePrefix("bill-")
  }

  private fun loadAddons(
    root: Path,
    repoToken: String,
    selections: MutableMap<String, SelectionDetail>,
  ): List<SkillBillTreeItem> {
    val addonsByPlatform = linkedMapOf<String, MutableList<SkillBillTreeItem>>()
    repoSourceDiscoveryService.discoverGovernedAddonFiles(root)
      .forEach { addonFile ->
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
                val groupPath = nativeAgentOwnerGroupPath(relative)
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
                    label = displayLabelForNativeAgent(agent.name, relative, groupPath),
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
              val groupPath = nativeAgentOwnerGroupPath(relative)
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
                    label = "${displayLabelForNativeAgent(
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

  private fun nativeAgentOwnerGroupPath(relativePath: String): List<String> {
    val parts = relativePath.split('/')
    if (parts.size >= 4 && parts[0] == "skills") {
      return listOf(parts[1].removePrefix("bill-"))
    }
    if (parts.size >= 5 && parts[0] == "platform-packs") {
      val platform = parts[1]
      val family = parts[2]
      val skillName = parts[3]
      val baselineName = "bill-$platform-$family"
      val areaPrefix = "$baselineName-"
      val area = skillName.removePrefix(areaPrefix).takeIf { skillName.startsWith(areaPrefix) }.orEmpty()
      return listOf(platform, displayLabelForSkill(skillName, platform, family, area))
    }
    return listOf("unowned")
  }

  private fun displayLabelForNativeAgent(
    agentName: String,
    relativePath: String,
    groupPath: List<String> = nativeAgentOwnerGroupPath(relativePath),
  ): String {
    val platform = platformFromRelativePath(relativePath)
    var label = agentName.removePrefix("bill-")
    if (platform.isNotBlank()) {
      label = label.removeDisplayPrefix("$platform-")
    }
    groupPath.forEach { groupLabel ->
      label = label.removeDisplayPrefix("$groupLabel-")
    }
    return label
  }

  private fun String.removeDisplayPrefix(prefix: String): String =
    if (startsWith(prefix) && length > prefix.length) removePrefix(prefix) else this

  private fun platformFromRelativePath(relativePath: String): String {
    val parts = relativePath.split('/')
    return if (parts.size >= 2 && parts[0] == "platform-packs") parts[1] else ""
  }

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

  private data class RepoBrowserSnapshot(
    val repoRoot: Path? = null,
    val repoToken: String? = null,
    val loadStatus: RepoLoadStatus = RepoLoadStatus.empty,
    val treeItems: List<SkillBillTreeItem> = emptyList(),
    val selections: Map<String, SelectionDetail> = emptyMap(),
  ) {
    companion object {
      val empty = RepoBrowserSnapshot()
    }
  }

  private data class TreeBuildResult(
    val items: List<SkillBillTreeItem>,
    val selections: Map<String, SelectionDetail>,
  )

  private data class AuthoredSkillGroups(
    val horizontal: List<SkillBillTreeItem>,
    val platform: List<SkillBillTreeItem>,
  )

  private data class SelectionDetail(
    val repoToken: String,
    val title: String,
    val detail: String,
    val skillName: String? = null,
    val kind: String? = null,
    val authoredPath: String? = null,
    val status: String? = null,
    val editable: Boolean = false,
    val readOnlyLabel: String? = null,
    val readOnlyReason: String? = null,
    val contentFile: Path? = null,
    val contentOverride: String? = null,
    val generatedArtifacts: List<GeneratedArtifactDetail> = emptyList(),
    val metadata: SkillBillTreeItemMetadata? = null,
  ) {
    fun toEditorPlaceholder(): EditorPlaceholder {
      val readResult =
        contentOverride
          ?.let { content -> Result.success(content) }
          ?: contentFile
            ?.takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
            ?.let { file -> runCatching { Files.readString(file) } }
      val content = readResult?.getOrNull()
      val resolvedDetail =
        readResult?.exceptionOrNull()?.message?.let { message ->
          "$detail Source could not be read: $message"
        } ?: detail
      return EditorPlaceholder(
        title = title,
        detail = resolvedDetail,
        skillName = skillName ?: metadata?.skillName,
        kind = kind ?: metadata?.kind,
        authoredPath = authoredPath,
        status = status,
        editable = canEdit(),
        readOnlyLabel = readOnlyLabel,
        readOnlyReason = if (canEdit()) null else readOnlyReasonForDocument(),
        content = content,
        generatedArtifacts = generatedArtifacts,
      )
    }

    fun toDocument(treeItemId: String?): AuthoredContentDocument {
      val content =
        contentOverride
          ?.let { rendered -> Result.success(rendered) }
          ?: contentFile
            ?.takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
            ?.let { file -> runCatching { Files.readString(file) } }
      return AuthoredContentDocument(
        treeItemId = treeItemId,
        title = title,
        skillName = skillName ?: metadata?.skillName,
        kind = kind ?: metadata?.kind,
        authoredPath = authoredPath,
        text = content?.getOrNull().orEmpty(),
        editable = canEdit(),
        readOnlyReason = if (canEdit()) null else readOnlyReasonForDocument(),
        runtimeErrorMessage = content?.exceptionOrNull()?.let(::runtimeMessage),
      )
    }

    fun canEdit(): Boolean = editable &&
      contentFile != null &&
      Files.isRegularFile(contentFile, LinkOption.NOFOLLOW_LINKS) &&
      !isInstallCachePath(contentFile) &&
      when {
        kind == "add-on" -> true
        skillName != null && (kind == "horizontal skill" || kind == "platform pack skill") ->
          isAuthoredContentFile(contentFile)
        else -> false
      }

    fun readOnlyReasonForDocument(): String = readOnlyReason
      ?: when {
        isInstallCachePath(contentFile) -> "Install cache files are runtime output and cannot be edited here."
        kind == "generated artifact" -> detail
        contentFile?.fileName?.toString() == "SKILL.md" ->
          "Generated SKILL.md is runtime output. Edit content.md instead."
        !isAuthoredContentFile(contentFile) -> AUTHORED_SOURCE_READ_ONLY_REASON
        else -> "This selection cannot enter editable mode."
      }
  }

  private fun selectionFor(session: RepoSession?, treeItemId: String?): SelectionDetail? {
    if (session?.isRecognizedSkillBillRepo != true || treeItemId == null) {
      return null
    }
    val capturedSnapshot = snapshot
    if (capturedSnapshot.repoRoot?.toString() != session.repoPath) {
      return null
    }
    return capturedSnapshot.selections[treeItemId]?.takeIf { it.repoToken == capturedSnapshot.repoToken }
  }

  companion object {
    val SkillItemKind = TreeItemKind.SKILL
    private const val READ_ONLY_LABEL = "RO"
    private const val AUTHORED_SOURCE_READ_ONLY_REASON =
      "Only governed content.md files and add-ons can be edited in this window."
  }
}

private fun resolveRepoPath(repoPath: String): Path? {
  val trimmed = repoPath.trim()
  if (trimmed.isBlank()) {
    return null
  }
  return try {
    Path.of(trimmed).toAbsolutePath().normalize()
  } catch (_: InvalidPathException) {
    null
  }
}

private fun invalidSession(repoPath: String, message: String): RepoSession = RepoSession(
  repoPath = repoPath,
  isRecognizedSkillBillRepo = false,
  loadStatus = RepoLoadStatus(
    state = RepoLoadState.INVALID,
    message = message,
  ),
)

private fun looksLikeSkillBillRepo(root: Path): Boolean =
  Files.isDirectory(root.resolve("skills")) || Files.isDirectory(root.resolve("platform-packs"))

private fun isAuthoredContentFile(contentFile: Path?): Boolean = contentFile?.fileName?.toString() == "content.md"

private fun group(id: String, label: String, children: List<SkillBillTreeItem>): SkillBillTreeItem? =
  children.takeIf { it.isNotEmpty() }?.let {
    SkillBillTreeItem(
      id = id,
      label = label,
      kind = TreeItemKind.GROUP,
      editable = false,
      children = it,
    )
  }

private fun relativePath(root: Path, path: String): String = relativePath(root, Path.of(path))

private fun relativePath(root: Path, path: Path): String {
  val absoluteRoot = root.toAbsolutePath().normalize()
  val absolutePath = path.toAbsolutePath().normalize()
  relativeToRootOrNull(absoluteRoot, absolutePath)?.let { relative ->
    return relative.portablePath()
  }

  val realRelative = runCatching {
    val realRoot = root.toRealPath()
    val realPath =
      if (Files.exists(absolutePath, LinkOption.NOFOLLOW_LINKS)) {
        absolutePath.toRealPath()
      } else {
        absolutePath
      }
    relativeToRootOrNull(realRoot, realPath)?.portablePath()
  }.getOrNull()

  return realRelative ?: path.portablePath()
}

private fun relativeToRootOrNull(root: Path, path: Path): Path? =
  if (path.startsWith(root)) path.relativeTo(root) else null

// SKILL-77 subtask 4: the baseline manifest keys each skill by its directory relative to
// the install root (e.g. `skills/bill-alpha`, `platform-packs/kmp/code-review/bill-x`). The
// authored content file lives directly inside that skill dir, so its parent IS the key.
private fun skillRelativeKey(root: Path, contentFile: String): String =
  relativePath(root, Path.of(contentFile).toAbsolutePath().normalize().parent)

private fun Path.portablePath(): String = toString().replace('\\', '/')

private fun repoToken(root: Path): String {
  val digest = MessageDigest.getInstance("SHA-256")
    .digest(root.toAbsolutePath().normalize().toString().toByteArray(Charsets.UTF_8))
  return digest.take(8).joinToString("") { byte -> "%02x".format(byte) }
}

private fun selectionId(repoToken: String, localId: String): String = "repo:$repoToken|$localId"

private fun normalizeSourcePath(root: Path, sourcePath: String): String {
  val trimmed = sourcePath.trim()
  if (trimmed.isBlank()) {
    return ""
  }
  return runCatching {
    val absolute = Path.of(trimmed)
    if (absolute.isAbsolute) {
      absolute.toAbsolutePath().normalize().relativeTo(root).portablePath()
    } else {
      // Already relative; portable-ify.
      trimmed.replace('\\', '/')
    }
  }.getOrDefault(trimmed.replace('\\', '/'))
}

private fun runtimeMessage(error: Throwable): String {
  val message = error.message
  return when {
    error is SkillBillRuntimeException && !message.isNullOrBlank() -> message
    !message.isNullOrBlank() -> message
    else -> error::class.simpleName ?: error::class.qualifiedName ?: "Runtime error"
  }
}

private fun isInstallCachePath(path: Path?): Boolean = path
  ?.toAbsolutePath()
  ?.normalize()
  ?.iterator()
  ?.asSequence()
  ?.map { part -> part.toString() }
  ?.windowed(2)
  ?.any { parts -> parts[0] == ".skill-bill" && parts[1] == "installed-skills" }
  ?: false
