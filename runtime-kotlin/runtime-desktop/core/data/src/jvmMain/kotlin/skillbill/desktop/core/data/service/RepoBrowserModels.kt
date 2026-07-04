package skillbill.desktop.core.data.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import skillbill.contracts.JsonSupport
import skillbill.desktop.core.domain.model.AuthoredContentDocument
import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.GeneratedArtifactDetail
import skillbill.desktop.core.domain.model.RepoLoadState
import skillbill.desktop.core.domain.model.RepoLoadStatus
import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.model.SkillBillTreeItem
import skillbill.desktop.core.domain.model.SkillBillTreeItemMetadata
import skillbill.desktop.core.domain.model.TreeItemKind
import skillbill.error.SkillBillRuntimeException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.relativeTo

internal val SkillItemKind = TreeItemKind.SKILL
internal const val READ_ONLY_LABEL = "RO"
internal const val AUTHORED_SOURCE_READ_ONLY_REASON =
  "Only governed content.md files and add-ons can be edited in this window."
internal const val SKILL_BILL_CONFIG_KIND = "skill-bill config"
internal const val DEFAULT_SKILL_BILL_CONFIG_DOCUMENT = "{\n  \"external_addon_sources\": []\n}\n"
private val PrettyConfigJson = Json {
  ignoreUnknownKeys = true
  explicitNulls = false
  prettyPrint = true
}

internal data class RepoBrowserSnapshot(
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

internal data class TreeBuildResult(
  val items: List<SkillBillTreeItem>,
  val selections: Map<String, SelectionDetail>,
)

internal data class SelectionDetail(
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
    val readResult = readContent()
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
    val content = readContent()
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
    !isInstallCachePath(contentFile) &&
    when {
      kind == SKILL_BILL_CONFIG_KIND ->
        !Files.exists(contentFile, LinkOption.NOFOLLOW_LINKS) ||
          Files.isRegularFile(contentFile, LinkOption.NOFOLLOW_LINKS)
      kind == "add-on" -> Files.isRegularFile(contentFile, LinkOption.NOFOLLOW_LINKS)
      skillName != null && (kind == "horizontal skill" || kind == "platform pack skill") ->
        Files.isRegularFile(contentFile, LinkOption.NOFOLLOW_LINKS) &&
          isAuthoredContentFile(contentFile)
      else -> false
    }

  private fun readContent(): Result<String>? = contentOverride
    ?.let { content -> Result.success(content) }
    ?: contentFile
      ?.let { file ->
        when {
          Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) -> runCatching {
            val raw = Files.readString(file)
            if (kind == SKILL_BILL_CONFIG_KIND) raw.prettySkillBillConfigOrRaw() else raw
          }
          kind == SKILL_BILL_CONFIG_KIND && !Files.exists(file, LinkOption.NOFOLLOW_LINKS) ->
            Result.success(DEFAULT_SKILL_BILL_CONFIG_DOCUMENT)
          else -> null
        }
      }

  fun readOnlyReasonForDocument(): String = readOnlyReason
    ?: when {
      isInstallCachePath(contentFile) -> "Install cache files are runtime output and cannot be edited here."
      kind == SKILL_BILL_CONFIG_KIND -> "Skill Bill config must be a regular JSON file."
      kind == "generated artifact" -> detail
      contentFile?.fileName?.toString() == "SKILL.md" ->
        "Generated SKILL.md is runtime output. Edit content.md instead."
      !isAuthoredContentFile(contentFile) -> AUTHORED_SOURCE_READ_ONLY_REASON
      else -> "This selection cannot enter editable mode."
    }
}

internal fun resolveRepoPath(repoPath: String): Path? {
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

internal fun invalidSession(repoPath: String, message: String): RepoSession = RepoSession(
  repoPath = repoPath,
  isRecognizedSkillBillRepo = false,
  loadStatus = RepoLoadStatus(
    state = RepoLoadState.INVALID,
    message = message,
  ),
)

internal fun looksLikeSkillBillRepo(root: Path): Boolean =
  Files.isDirectory(root.resolve("skills")) || Files.isDirectory(root.resolve("platform-packs"))

internal fun isAuthoredContentFile(contentFile: Path?): Boolean = contentFile?.fileName?.toString() == "content.md"

internal fun group(id: String, label: String, children: List<SkillBillTreeItem>): SkillBillTreeItem? =
  children.takeIf { it.isNotEmpty() }?.let {
    SkillBillTreeItem(
      id = id,
      label = label,
      kind = TreeItemKind.GROUP,
      editable = false,
      children = it,
    )
  }

internal fun relativePath(root: Path, path: String): String = relativePath(root, Path.of(path))

internal fun relativePath(root: Path, path: Path): String {
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

internal fun skillRelativeKey(root: Path, contentFile: String): String =
  relativePath(root, Path.of(contentFile).toAbsolutePath().normalize().parent)

private fun Path.portablePath(): String = toString().replace('\\', '/')

internal fun repoToken(root: Path): String {
  val digest = MessageDigest.getInstance("SHA-256")
    .digest(root.toAbsolutePath().normalize().toString().toByteArray(Charsets.UTF_8))
  return digest.take(8).joinToString("") { byte -> "%02x".format(byte) }
}

internal fun selectionId(repoToken: String, localId: String): String = "repo:$repoToken|$localId"

internal fun normalizeSourcePath(root: Path, sourcePath: String): String {
  val trimmed = sourcePath.trim()
  if (trimmed.isBlank()) {
    return ""
  }
  return runCatching {
    val absolute = Path.of(trimmed)
    if (absolute.isAbsolute) {
      absolute.toAbsolutePath().normalize().relativeTo(root).portablePath()
    } else {
      trimmed.replace('\\', '/')
    }
  }.getOrDefault(trimmed.replace('\\', '/'))
}

internal fun runtimeMessage(error: Throwable): String {
  val message = error.message
  return when {
    error is SkillBillRuntimeException && !message.isNullOrBlank() -> message
    !message.isNullOrBlank() -> message
    else -> error::class.simpleName ?: error::class.qualifiedName ?: "Runtime error"
  }
}

internal fun isInstallCachePath(path: Path?): Boolean = path
  ?.toAbsolutePath()
  ?.normalize()
  ?.iterator()
  ?.asSequence()
  ?.map { part -> part.toString() }
  ?.windowed(2)
  ?.any { parts -> parts[0] == ".skill-bill" && parts[1] == "installed-skills" }
  ?: false

internal fun String.prettySkillBillConfigOrNull(): String? {
  val parsed = JsonSupport.parseObjectOrNull(this) ?: return null
  return PrettyConfigJson.encodeToString(JsonObject.serializer(), parsed) + "\n"
}

internal fun String.prettySkillBillConfigOrRaw(): String = prettySkillBillConfigOrNull() ?: this
