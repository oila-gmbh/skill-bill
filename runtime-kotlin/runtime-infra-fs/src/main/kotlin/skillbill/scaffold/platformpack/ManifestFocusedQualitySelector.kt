package skillbill.scaffold.platformpack

import skillbill.ports.taskruntime.FeatureTaskRuntimeFocusedQualitySelector
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFocusedQualityCategory
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFocusedQualityCheck
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFocusedQualitySelection
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Selects bounded focused checks from workflow-owned paths and manifest-declared quality checkers.
 * It returns executable check descriptors; the injected runner remains responsible for commands.
 */
class ManifestFocusedQualitySelector(
  private val platformPacksRoot: Path,
) : FeatureTaskRuntimeFocusedQualitySelector {
  override fun select(ownedPaths: List<String>): FeatureTaskRuntimeFocusedQualitySelection {
    val normalized = ownedPaths.distinct().sorted()
    require(normalized.isNotEmpty())
    val routed = discoverPlatformPacks(platformPacksRoot)
      .filter { pack ->
        pack.declaredQualityCheckFile != null &&
          normalized.any { path -> pack.routingSignals.path.any { signal -> matches(signal, path) } }
      }
    require(routed.isNotEmpty()) {
      "No manifest-declared quality checker matches the workflow-owned path inventory."
    }
    val checks = routed
      .flatMap { pack ->
        val checker = pack.declaredQualityCheckFile
          ?.let(pack.packRoot::relativize)
          ?.toString()
          ?.replace('\\', '/')
          ?: error("Matched pack '${pack.slug}' has no declared quality checker.")
        FeatureTaskRuntimeFocusedQualityCategory.entries.map { category ->
          FeatureTaskRuntimeFocusedQualityCheck(
            checkId = "${pack.slug}:${category.name.lowercase()}",
            category = category,
            ownedPaths = normalized,
            checkerSkill = checker,
          )
        }
      }
      .sortedBy { it.checkId }
    val material = buildString {
      val repoRoot = platformPacksRoot.toAbsolutePath().normalize().parent
      normalized.forEach { path ->
        append("path:").append(path).append(':')
        val resolved = repoRoot.resolve(path).normalize()
        require(resolved.startsWith(repoRoot)) { "Owned path escapes the repository: '$path'." }
        if (Files.isRegularFile(resolved)) {
          append(sha256(Files.readString(resolved)))
        } else {
          append("missing")
        }
        append('\n')
      }
      checks.forEach { append("check:").append(it.checkId).append(':').append(it.checkerSkill).append('\n') }
    }
    return FeatureTaskRuntimeFocusedQualitySelection(
      semanticFingerprint = sha256(material),
      checks = checks,
    )
  }

  private fun matches(signal: String, path: String): Boolean {
    val normalizedSignal = signal.removePrefix("./")
    return if ('*' in normalizedSignal || '?' in normalizedSignal || '[' in normalizedSignal) {
      val matcher = FileSystems.getDefault().getPathMatcher("glob:$normalizedSignal")
      val candidate = Path.of(path)
      matcher.matches(candidate) || candidate.fileName?.let(matcher::matches) == true
    } else {
      path == normalizedSignal ||
        path.startsWith("$normalizedSignal/") ||
        (normalizedSignal.startsWith('.') && path.endsWith(normalizedSignal))
    }
  }

  private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
}
