package skillbill.desktop.core.data.service

import me.tatarka.inject.annotations.Inject
import skillbill.desktop.core.common.di.UserScope
import skillbill.desktop.core.domain.model.AuthoredContentDocument
import skillbill.desktop.core.domain.model.AuthoringSaveResult
import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.GeneratedArtifactDetail
import skillbill.desktop.core.domain.model.RenderBlock
import skillbill.desktop.core.domain.model.RenderRunState
import skillbill.desktop.core.domain.model.RenderSummary
import skillbill.desktop.core.domain.model.RepoLoadState
import skillbill.desktop.core.domain.model.RepoLoadStatus
import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.model.SkillBillTreeItem
import skillbill.desktop.core.domain.model.SkillBillTreeItemMetadata
import skillbill.desktop.core.domain.model.TreeItemKind
import skillbill.desktop.core.domain.model.ValidationIssue
import skillbill.desktop.core.domain.model.ValidationRunState
import skillbill.desktop.core.domain.model.ValidationSeverity
import skillbill.desktop.core.domain.model.ValidationSummary
import skillbill.desktop.core.domain.service.AuthoringGateway
import skillbill.desktop.core.domain.service.RenderGateway
import skillbill.desktop.core.domain.service.RepoSessionService
import skillbill.desktop.core.domain.service.SkillTreeService
import skillbill.desktop.core.domain.service.ValidationGateway
import skillbill.error.SkillBillRuntimeException
import skillbill.nativeagent.discoverNativeAgentSourceFiles
import skillbill.nativeagent.parseNativeAgentSourceFile
import skillbill.nativeagent.renderNativeAgentSource
import skillbill.scaffold.AuthoringOperations
import skillbill.scaffold.AuthoringRenderResult
import skillbill.scaffold.RepoValidationIssueSeverity
import skillbill.scaffold.RepoValidationReport
import skillbill.scaffold.RepoValidationRuntime
import skillbill.scaffold.discoverGeneratedArtifactFiles
import skillbill.scaffold.discoverGovernedAddonFiles
import skillbill.scaffold.renderAuthoringTarget
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.isDirectory
import kotlin.io.path.relativeTo

@Inject
@SingleIn(UserScope::class)
class RuntimeRepoBrowserService :
  RepoSessionService,
  SkillTreeService,
  AuthoringGateway,
  ValidationGateway,
  RenderGateway {
  // F-107: validator is a functional seam tests can swap to drive the onFailure branch of validate()
  // without needing to physically corrupt a repo on disk.
  internal var validator: (Path) -> RepoValidationReport = RepoValidationRuntime::validateRepo

  // Mirror of F-107 for render. Tests swap this to drive the runtime-failure branch of render()
  // without needing to physically corrupt the on-disk source.
  internal var renderer: (Path, String) -> AuthoringRenderResult = ::renderAuthoringTarget
  internal var authoringSaver: (Path, String, String) -> Map<String, Any?> = { root, skillName, body ->
    AuthoringOperations.fill(root, skillName, body, sectionName = null)
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
    val skillName = detail.skillName
      ?: return AuthoringSaveResult.failed("Only governed skill content.md files can be saved through authoring.")
    return runCatching {
      authoringSaver(root, skillName, body)
      AuthoringSaveResult(
        success = true,
        document = detail.toDocument(treeItemId),
      )
    }.getOrElse { error ->
      AuthoringSaveResult.failed(runtimeMessage(error))
    }
  }

  override fun validate(session: RepoSession?): ValidationSummary {
    if (session == null || !session.isRecognizedSkillBillRepo) {
      return ValidationSummary.unavailable
    }
    val root = resolveRepoPath(session.repoPath) ?: return ValidationSummary.unavailable
    return runCatching { validator(root) }
      .fold(
        onSuccess = { report ->
          val issues = report.structuredIssues.map { issue ->
            ValidationIssue(
              severity = issue.severity.toDomain(),
              code = issue.code,
              message = issue.message,
              sourcePath = issue.sourcePath?.let { path -> normalizeSourcePath(root, path) },
              exceptionName = issue.exceptionName,
            )
          }
          ValidationSummary(
            state = if (report.passed) ValidationRunState.PASSED else ValidationRunState.FAILED,
            issues = issues,
          )
        },
        onFailure = { error ->
          ValidationSummary(
            state = ValidationRunState.FAILED,
            issues = emptyList(),
            runtimeExceptionName = error::class.simpleName ?: error::class.qualifiedName,
            runtimeExceptionMessage = error.message,
          )
        },
      )
  }

  override fun render(session: RepoSession?, treeItemId: String): RenderSummary {
    if (session == null || !session.isRecognizedSkillBillRepo) {
      return RenderSummary.unavailable
    }
    // F-201: capture the snapshot reference once so we get a consistent view across reads even if
    // open()/refresh() rewrites `snapshot` from another thread mid-render.
    val capturedSnapshot = snapshot
    val root = capturedSnapshot.repoRoot ?: return RenderSummary.unavailable
    if (root.toString() != session.repoPath) {
      return RenderSummary.unavailable
    }
    val detail = capturedSnapshot.selections[treeItemId]?.takeIf { it.repoToken == capturedSnapshot.repoToken }
      ?: return RenderSummary.unavailable
    val start = System.nanoTime()
    return runCatching { renderDetail(root, detail) }
      .fold(
        onSuccess = { (blocks, artifacts) ->
          RenderSummary(
            state = RenderRunState.PASSED,
            blocks = blocks,
            generatedArtifacts = artifacts,
            durationMillis = elapsedMillis(start),
          )
        },
        onFailure = { error ->
          RenderSummary(
            state = RenderRunState.FAILED,
            blocks = emptyList(),
            generatedArtifacts = emptyList(),
            durationMillis = elapsedMillis(start),
            runtimeExceptionName = error::class.simpleName ?: error::class.qualifiedName,
            runtimeExceptionMessage = error.message,
          )
        },
      )
  }

  private fun renderDetail(root: Path, detail: SelectionDetail): RenderOutcome = when (detail.kind) {
    "horizontal skill", "platform pack skill" -> renderSkill(root, detail)
    "native agent" -> renderNativeAgent(root, detail)
    "add-on" -> renderAddon(root, detail)
    else -> error("Render is not supported for selection kind '${detail.kind ?: "unknown"}'.")
  }

  private fun renderSkill(root: Path, detail: SelectionDetail): RenderOutcome {
    val skillName = detail.skillName ?: error("Skill selection is missing a skill name.")
    val result = renderer(root, skillName)
    val blocks = result.blocks.map { block -> RenderBlock(header = block.header, content = block.content) }
    val artifacts = result.blocks.mapNotNull { block -> generatedArtifactFromHeader(block.header) }
    return RenderOutcome(blocks = blocks, generatedArtifacts = artifacts)
  }

  private fun renderNativeAgent(root: Path, detail: SelectionDetail): RenderOutcome {
    val contentFile = detail.contentFile
      ?: error("Native agent selection is missing a content file.")
    val agents = parseNativeAgentSourceFile(contentFile)
    val relative = relativePath(root, contentFile.toString())
    val blocks = agents.map { agent ->
      RenderBlock(
        header = "===== native-agent: $relative:${agent.name} =====",
        content = renderNativeAgentSource(agent),
      )
    }
    return RenderOutcome(blocks = blocks, generatedArtifacts = emptyList())
  }

  private fun renderAddon(root: Path, detail: SelectionDetail): RenderOutcome {
    val contentFile = detail.contentFile
      ?: error("Add-on selection is missing a content file.")
    val relative = relativePath(root, contentFile.toString())
    val content = Files.readString(contentFile)
    return RenderOutcome(
      blocks = listOf(RenderBlock(header = "===== addon: $relative =====", content = content)),
      generatedArtifacts = emptyList(),
    )
  }

  override fun resolveTreeItemIdForSource(session: RepoSession?, sourcePath: String): String? {
    if (session?.isRecognizedSkillBillRepo != true) {
      return null
    }
    val expectedRepoPath = snapshot.repoRoot?.toString()
    if (expectedRepoPath != session.repoPath) {
      return null
    }
    val root = snapshot.repoRoot ?: return null
    val normalized = normalizeSourcePath(root, sourcePath)
    if (normalized.isBlank()) {
      return null
    }
    return snapshot.selections.entries
      .firstOrNull { (_, detail) -> matchesSourcePath(detail.authoredPath, normalized) }
      ?.key
  }

  private fun validateRoot(root: Path): RepoLoadStatus {
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
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

    return runCatching { RepoValidationRuntime.validateRepo(root) }
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
    val payload = AuthoringOperations.list(root, emptyList())
    val skills = payload["skills"] as? List<*> ?: emptyList<Any?>()
    val horizontal = mutableListOf<SkillBillTreeItem>()
    val platformChildren = linkedMapOf<String, MutableList<SkillBillTreeItem>>()
    skills.filterIsInstance<Map<*, *>>().forEach { skill ->
      val skillName = skill.string("skill_name") ?: return@forEach
      val platform = skill.string("platform").orEmpty()
      val family = skill.string("family").orEmpty()
      val area = skill.string("area").orEmpty()
      val contentFile = skill.string("content_file").orEmpty()
      val status = skill.string("completion_status").orEmpty()
      val kind = if (platform.isBlank()) "horizontal skill" else "platform pack skill"
      val id = selectionId(repoToken, "skill:$skillName")
      val metadata =
        SkillBillTreeItemMetadata(
          skillName = skillName,
          kind = kind,
          packageName = skill.string("package"),
          platform = platform.takeIf(String::isNotBlank),
          family = family.takeIf(String::isNotBlank),
          area = area.takeIf(String::isNotBlank),
        )
      val item =
        SkillBillTreeItem(
          id = id,
          label = skillName,
          kind = SkillItemKind,
          authoredPath = relativePath(root, contentFile),
          status = status,
          editable = true,
          metadata = metadata,
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
  ): List<SkillBillTreeItem> {
    return discoverGovernedAddonFiles(root)
      .map { addonFile ->
        val addon = addonFile.addonPath
        val relative = addon.relativeTo(root).portablePath()
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
            readOnlyLabel = READ_ONLY_LABEL,
            readOnlyReason = "Add-ons are not editable in this workbench.",
          )
        SkillBillTreeItem(
          id = id,
          label = addonFile.addonSlug,
          kind = TreeItemKind.ADD_ON,
          authoredPath = relative,
          status = "authored",
          editable = false,
          metadata = SkillBillTreeItemMetadata(kind = "add-on", platform = addonFile.packSlug),
          readOnlyLabel = READ_ONLY_LABEL,
        )
      }
  }

  private fun loadNativeAgents(
    root: Path,
    repoToken: String,
    selections: MutableMap<String, SelectionDetail>,
  ): List<SkillBillTreeItem> = discoverNativeAgentSourceFiles(
    platformPacksRoot = root.resolve("platform-packs"),
    skillsRoot = root.resolve("skills"),
  )
    .flatMap { sourceFile ->
      val relative = sourceFile.relativeTo(root).portablePath()
      runCatching { parseNativeAgentSourceFile(sourceFile) }
        .fold(
          onSuccess = { agents ->
            agents.map { agent ->
              val id = selectionId(repoToken, "native-agent:$relative:${agent.bundleEntryName ?: agent.name}")
              selections[id] =
                SelectionDetail(
                  repoToken = repoToken,
                  title = agent.name,
                  detail = "Provider-neutral native-agent source.",
                  kind = "native agent",
                  authoredPath = relative,
                  status = agent.composition?.kind?.wireValue ?: "authored",
                  contentFile = sourceFile,
                  readOnlyLabel = READ_ONLY_LABEL,
                  readOnlyReason = "Native-agent source editing is not supported in this workbench.",
                )
              SkillBillTreeItem(
                id = id,
                label = agent.name,
                kind = TreeItemKind.NATIVE_AGENT,
                authoredPath = relative,
                status = agent.composition?.kind?.wireValue ?: "authored",
                editable = false,
                readOnlyLabel = READ_ONLY_LABEL,
                metadata = SkillBillTreeItemMetadata(kind = "native agent"),
              )
            }
          },
          onFailure = { error ->
            val id = selectionId(repoToken, "native-agent-error:$relative")
            val message = error.message.orEmpty().ifBlank { "Native-agent source could not be parsed." }
            selections[id] =
              SelectionDetail(
                repoToken = repoToken,
                title = sourceFile.fileName.toString(),
                detail = "Invalid native-agent source: $message",
                kind = "native agent",
                authoredPath = relative,
                status = "invalid",
                contentFile = sourceFile,
                readOnlyLabel = READ_ONLY_LABEL,
                readOnlyReason = "Invalid native-agent sources cannot enter editable mode.",
              )
            listOf(
              SkillBillTreeItem(
                id = id,
                label = "${sourceFile.fileName} (invalid)",
                kind = TreeItemKind.NATIVE_AGENT,
                authoredPath = relative,
                status = "invalid",
                editable = false,
                readOnlyLabel = READ_ONLY_LABEL,
                metadata = SkillBillTreeItemMetadata(kind = "native agent"),
              ),
            )
          },
        )
    }
    .sortedBy { item -> item.label }

  private fun loadGeneratedArtifacts(
    root: Path,
    repoToken: String,
    selections: MutableMap<String, SelectionDetail>,
  ): List<SkillBillTreeItem> {
    return discoverGeneratedArtifactFiles(root).map { generated ->
      val artifact = generated.path
      val relative = artifact.relativeTo(root).portablePath()
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
    return discoverGeneratedArtifactFiles(root)
      .filter { artifact -> artifact.path.toAbsolutePath().normalize().parent == normalizedSkillDir }
      .map { artifact ->
        GeneratedArtifactDetail(
          path = artifact.path.relativeTo(root).portablePath(),
          reason = artifact.reason,
        )
      }
  }

  private data class RenderOutcome(
    val blocks: List<RenderBlock>,
    val generatedArtifacts: List<GeneratedArtifactDetail>,
  )

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
    val generatedArtifacts: List<GeneratedArtifactDetail> = emptyList(),
    val metadata: SkillBillTreeItemMetadata? = null,
  ) {
    fun toEditorPlaceholder(): EditorPlaceholder {
      val readResult =
        contentFile
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
        editable = editable,
        readOnlyLabel = readOnlyLabel,
        readOnlyReason = if (editable) null else readOnlyReasonForDocument(),
        content = content,
        generatedArtifacts = generatedArtifacts,
      )
    }

    fun toDocument(treeItemId: String?): AuthoredContentDocument {
      val content = contentFile
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

    fun canEdit(): Boolean =
      editable &&
        contentFile != null &&
        (kind == "horizontal skill" || kind == "platform pack skill") &&
        Files.isRegularFile(contentFile, LinkOption.NOFOLLOW_LINKS) &&
        !isInstallCachePath(contentFile) &&
        contentFile.fileName.toString() == "content.md"

    fun readOnlyReasonForDocument(): String =
      readOnlyReason
        ?: when {
          isInstallCachePath(contentFile) -> "Install cache files are runtime output and cannot be edited here."
          kind == "generated artifact" -> detail
          kind == "native agent" -> "Provider-native output and native-agent sources cannot enter editable mode here."
          kind == "add-on" -> "Add-ons are not editable in this workbench."
          contentFile?.fileName?.toString() == "SKILL.md" -> "Generated SKILL.md is runtime output. Edit content.md instead."
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
  root.resolve("skills").isDirectory() || root.resolve("platform-packs").isDirectory()

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

private fun Map<*, *>.string(key: String): String? = this[key] as? String

private fun relativePath(root: Path, path: String): String =
  runCatching { Path.of(path).toAbsolutePath().normalize().relativeTo(root).portablePath() }
    .getOrDefault(path.replace('\\', '/'))

private fun Path.portablePath(): String = toString().replace('\\', '/')

private fun repoToken(root: Path): String {
  val digest = MessageDigest.getInstance("SHA-256")
    .digest(root.toAbsolutePath().normalize().toString().toByteArray(Charsets.UTF_8))
  return digest.take(8).joinToString("") { byte -> "%02x".format(byte) }
}

private fun selectionId(repoToken: String, localId: String): String = "repo:$repoToken|$localId"

private fun RepoValidationIssueSeverity.toDomain(): ValidationSeverity = when (this) {
  RepoValidationIssueSeverity.ERROR -> ValidationSeverity.ERROR
  RepoValidationIssueSeverity.WARNING -> ValidationSeverity.WARNING
  RepoValidationIssueSeverity.INFO -> ValidationSeverity.INFO
}

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

private val RENDER_ARTIFACT_HEADER_REGEX = Regex("^===== (SKILL\\.md|pointer): (.+) =====$")

private fun generatedArtifactFromHeader(header: String): GeneratedArtifactDetail? {
  val match = RENDER_ARTIFACT_HEADER_REGEX.matchEntire(header) ?: return null
  val kind = match.groupValues[1]
  val path = match.groupValues[2]
  val reason = when (kind) {
    "SKILL.md" -> "Generated runtime wrapper"
    "pointer" -> "Generated pointer"
    else -> "Generated artifact"
  }
  return GeneratedArtifactDetail(path = path, reason = reason)
}

private fun elapsedMillis(startNanos: Long): Long = (System.nanoTime() - startNanos).coerceAtLeast(0L) / 1_000_000L

private fun runtimeMessage(error: Throwable): String {
  val message = error.message
  return when {
    error is SkillBillRuntimeException && !message.isNullOrBlank() -> message
    !message.isNullOrBlank() -> message
    else -> error::class.simpleName ?: error::class.qualifiedName ?: "Runtime error"
  }
}

private fun isInstallCachePath(path: Path?): Boolean =
  path
    ?.toAbsolutePath()
    ?.normalize()
    ?.iterator()
    ?.asSequence()
    ?.map { part -> part.toString() }
    ?.windowed(2)
    ?.any { parts -> parts[0] == ".skill-bill" && parts[1] == "installed-skills" }
    ?: false

private fun matchesSourcePath(authoredPath: String?, sourcePath: String): Boolean {
  if (authoredPath.isNullOrBlank() || sourcePath.isBlank()) {
    return false
  }
  val normalizedAuthored = authoredPath.replace('\\', '/')
  if (normalizedAuthored == sourcePath) {
    return true
  }
  // Allow matching on the parent directory of an authored file (e.g. content.md path vs SKILL.md sibling).
  val authoredDir = normalizedAuthored.substringBeforeLast('/', missingDelimiterValue = "")
  val sourceDir = sourcePath.substringBeforeLast('/', missingDelimiterValue = "")
  return authoredDir.isNotEmpty() && sourceDir.isNotEmpty() && authoredDir == sourceDir
}
