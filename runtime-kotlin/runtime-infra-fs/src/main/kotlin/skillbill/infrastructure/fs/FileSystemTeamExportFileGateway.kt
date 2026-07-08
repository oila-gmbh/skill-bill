package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.ports.team.TeamExportFileGateway
import skillbill.ports.team.TeamExportFileGatewayException
import skillbill.ports.team.model.TeamExportCollectedSource
import skillbill.ports.team.model.TeamExportRegistryPublishRequest
import skillbill.scaffold.platformpack.SKILL_CLASSES_DIR
import skillbill.scaffold.runtime.supportingFileTargets
import skillbill.team.model.TeamBundleSourceCategory
import skillbill.team.model.TeamExportRegistryDestination
import java.io.ByteArrayOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.name
import kotlin.io.path.relativeTo

@Inject
class FileSystemTeamExportFileGateway : TeamExportFileGateway {
  private val platformPackSkillRoots = setOf("code-review", "quality-check")

  override fun collectSources(repoRoot: Path): List<TeamExportCollectedSource> {
    val candidates = mutableListOf<TeamExportCollectedSource>()
    collectSkillSources(repoRoot, candidates)
    collectPlatformPackSources(repoRoot, candidates)
    collectOrchestrationSources(repoRoot, candidates)
    collectRootSupportSources(repoRoot, candidates)
    return candidates.distinctBy { it.path }.sortedBy { it.path }
  }

  override fun archiveBytes(
    metadataJson: String,
    sources: List<TeamExportCollectedSource>,
    repoRoot: Path,
  ): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
      zip.putStableEntry("bundle.json", metadataJson.toByteArray(Charsets.UTF_8))
      sources.sortedBy { it.path }.forEach { source ->
        zip.putStableEntry("sources/${source.path}", Files.readAllBytes(repoRoot.resolve(source.path)))
      }
    }
    return output.toByteArray()
  }

  override fun writeDirectBundle(path: Path, archive: ByteArray, metadataJson: String, checksum: String) {
    writeFileAtomically(path, archive)
    writeStringAtomically(path.resolveSibling("${path.name}.json"), "$metadataJson\n")
    writeStringAtomically(path.resolveSibling("${path.name}.sha256"), "$checksum  ${path.name}\n")
  }

  override fun publishRegistry(request: TeamExportRegistryPublishRequest): TeamExportRegistryDestination {
    val finalDir = request.registryRoot.resolve(request.channel).resolve(request.version).resolve(request.bundleId)
    if (Files.exists(finalDir)) {
      throw TeamExportFileGatewayException("Registry destination already exists: $finalDir")
    }
    Files.createDirectories(finalDir.parent)
    val tempDir = Files.createTempDirectory(request.registryRoot, ".team-export-${request.bundleId}-")
    var published = false
    try {
      Files.write(tempDir.resolve("bundle.zip"), request.archive)
      Files.writeString(tempDir.resolve("bundle.json"), "${request.metadataJson}\n")
      Files.writeString(tempDir.resolve("checksum.sha256"), "${request.checksum}  bundle.zip\n")
      if (request.failAfterTempWrite) {
        throw TeamExportFileGatewayException("Injected registry publish failure.")
      }
      moveDirectoryAtomically(tempDir, finalDir)
      published = true
      return TeamExportRegistryDestination(finalDir, request.channel, request.version, request.bundleId)
    } finally {
      if (!published) {
        deleteRecursively(tempDir)
      }
    }
  }

  private fun collectSkillSources(repoRoot: Path, candidates: MutableList<TeamExportCollectedSource>) {
    val skillsRoot = repoRoot.resolve("skills")
    if (!Files.isDirectory(skillsRoot)) return
    Files.list(skillsRoot).use { skills ->
      skills.filter(Files::isDirectory).forEach { skillRoot ->
        addIfRegular(repoRoot, candidates, skillRoot.resolve("content.md"), TeamBundleSourceCategory.HORIZONTAL_SKILL)
        collectNativeAgentSources(repoRoot, candidates, skillRoot)
      }
    }
  }

  private fun collectPlatformPackSources(repoRoot: Path, candidates: MutableList<TeamExportCollectedSource>) {
    val packsRoot = repoRoot.resolve("platform-packs")
    if (!Files.isDirectory(packsRoot)) return
    Files.list(packsRoot).use { packs ->
      packs.filter(Files::isDirectory).forEach { packRoot ->
        addIfRegular(repoRoot, candidates, packRoot.resolve("platform.yaml"), TeamBundleSourceCategory.PLATFORM_PACK)
        Files.walk(packRoot).use { paths ->
          paths.filter(Files::isRegularFile).forEach { path ->
            val relative = path.relativeTo(packRoot).toString().replace('\\', '/')
            when {
              relative == "platform.yaml" -> Unit
              relative == "content.md" || relative.endsWith("/content.md") ->
                addIfRegular(repoRoot, candidates, path, TeamBundleSourceCategory.PLATFORM_PACK)
              relative.startsWith("addons/") ->
                addIfRegular(repoRoot, candidates, path, TeamBundleSourceCategory.ADDON)
              relative.contains("/native-agents/") ->
                addIfRegular(repoRoot, candidates, path, TeamBundleSourceCategory.NATIVE_AGENT_SOURCE)
              isPlatformPackSkillFile(relative) ->
                addIfRegular(repoRoot, candidates, path, TeamBundleSourceCategory.PLATFORM_PACK)
            }
          }
        }
      }
    }
  }

  private fun isPlatformPackSkillFile(relativePath: String): Boolean {
    val segments = relativePath.split('/')
    val root = segments.firstOrNull()
    val skill = segments.getOrNull(1)
    val file = segments.getOrNull(2)
    return root in platformPackSkillRoots && skill != null && file != null
  }

  private fun collectNativeAgentSources(
    repoRoot: Path,
    candidates: MutableList<TeamExportCollectedSource>,
    skillRoot: Path,
  ) {
    val nativeAgents = skillRoot.resolve("native-agents")
    if (!Files.isDirectory(nativeAgents)) return
    Files.walk(nativeAgents).use { paths ->
      paths.filter(Files::isRegularFile).forEach { path ->
        addIfRegular(repoRoot, candidates, path, TeamBundleSourceCategory.NATIVE_AGENT_SOURCE)
      }
    }
  }

  private fun collectOrchestrationSources(repoRoot: Path, candidates: MutableList<TeamExportCollectedSource>) {
    collectRegularFiles(repoRoot.resolve("orchestration/contracts")).forEach { path ->
      addIfRegular(repoRoot, candidates, path, TeamBundleSourceCategory.ORCHESTRATION_CONTRACT_OR_SUPPORT)
    }
    collectRegularFiles(repoRoot.resolve(SKILL_CLASSES_DIR)).forEach { path ->
      addIfRegular(repoRoot, candidates, path, TeamBundleSourceCategory.ORCHESTRATION_CONTRACT_OR_SUPPORT)
    }
    supportingFileTargets(repoRoot).values
      .distinctBy { path -> path.toAbsolutePath().normalize() }
      .forEach { path ->
        addIfRegular(repoRoot, candidates, path, TeamBundleSourceCategory.ORCHESTRATION_CONTRACT_OR_SUPPORT)
      }
  }

  private fun addIfRegular(
    repoRoot: Path,
    candidates: MutableList<TeamExportCollectedSource>,
    path: Path,
    category: TeamBundleSourceCategory,
  ) {
    if (!Files.isRegularFile(path)) return
    val relative = path.toAbsolutePath().normalize().relativeTo(repoRoot).toString().replace('\\', '/')
    candidates += TeamExportCollectedSource(
      category = category,
      path = relative,
      contentHash = sha256(Files.readAllBytes(path)),
    )
  }
}

private fun collectRootSupportSources(repoRoot: Path, candidates: MutableList<TeamExportCollectedSource>) {
  listOf(
    repoRoot.resolve("README.md"),
    repoRoot.resolve(".agents/skill-overrides.example.md"),
    repoRoot.resolve(".agents/skill-overrides.md"),
    repoRoot.resolve(".claude-plugin/plugin.json"),
  ).forEach { path ->
    addRootSupportSource(repoRoot, candidates, path)
  }
}

private fun addRootSupportSource(repoRoot: Path, candidates: MutableList<TeamExportCollectedSource>, path: Path) {
  if (!Files.isRegularFile(path)) return
  val relative = path.toAbsolutePath().normalize().relativeTo(repoRoot).toString().replace('\\', '/')
  candidates += TeamExportCollectedSource(
    category = TeamBundleSourceCategory.ORCHESTRATION_CONTRACT_OR_SUPPORT,
    path = relative,
    contentHash = sha256(Files.readAllBytes(path)),
  )
}

private fun collectRegularFiles(root: Path): List<Path> {
  if (!Files.isDirectory(root)) return emptyList()
  return Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).toList() }
}

private fun ZipOutputStream.putStableEntry(name: String, bytes: ByteArray) {
  val crc = CRC32().apply { update(bytes) }
  val entry = ZipEntry(name).apply {
    method = ZipEntry.STORED
    size = bytes.size.toLong()
    compressedSize = bytes.size.toLong()
    this.crc = crc.value
    time = 0L
  }
  putNextEntry(entry)
  write(bytes)
  closeEntry()
}

private fun writeStringAtomically(path: Path, content: String) {
  writeFileAtomically(path, content.toByteArray(Charsets.UTF_8))
}

private fun writeFileAtomically(path: Path, bytes: ByteArray) {
  Files.createDirectories(path.parent ?: Path.of("."))
  val temp = Files.createTempFile(path.parent ?: Path.of("."), ".${path.name}.", ".tmp")
  try {
    Files.write(temp, bytes)
    moveFileAtomically(temp, path)
  } finally {
    Files.deleteIfExists(temp)
  }
}

private fun moveFileAtomically(source: Path, target: Path) {
  try {
    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
  } catch (_: AtomicMoveNotSupportedException) {
    Files.move(source, target)
  }
}

private fun moveDirectoryAtomically(source: Path, target: Path) {
  try {
    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
  } catch (error: AtomicMoveNotSupportedException) {
    throw TeamExportFileGatewayException("Atomic registry publish is not supported for $target.", error)
  }
}

private fun deleteRecursively(path: Path) {
  if (!Files.exists(path)) return
  Files.walk(path).use { paths ->
    paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
  }
}

private fun sha256(bytes: ByteArray): String {
  val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
  return "sha256:" + digest.joinToString("") { "%02x".format(it) }
}
