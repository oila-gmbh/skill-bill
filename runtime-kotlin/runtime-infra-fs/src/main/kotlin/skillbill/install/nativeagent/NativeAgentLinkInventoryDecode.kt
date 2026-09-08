package skillbill.install.nativeagent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import skillbill.error.InvalidNativeAgentLinkInventorySchemaError
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

internal object NativeAgentLinkInventoryDecode {
  fun decode(
    path: Path,
    home: Path,
    managedRoots: List<Path>,
    mapper: ObjectMapper,
    schema: JsonSchema,
  ): List<NativeAgentLinkInventoryEntry> {
    var failure: Throwable? = null
    var decoded: List<NativeAgentLinkInventoryEntry>? = null
    try {
      require(Files.size(path) <= NativeAgentLinkInventoryLimits.MAX_BYTES) {
        "inventory exceeds ${NativeAgentLinkInventoryLimits.MAX_BYTES} bytes"
      }
      val root = mapper.readTree(path.toFile())
      val schemaErrors = schema.validate(root)
      require(schemaErrors.isEmpty()) { schemaErrors.joinToString("; ") { it.message } }
      val entries = root["entries"]?.elements()?.asSequence()?.map { node ->
        NativeAgentLinkInventoryEntry(
          logicalName = node.requiredText("logical_name"),
          provider = node.requiredText("provider"),
          installedPath = Path.of(node.requiredText("installed_path")),
          cacheTargetPath = Path.of(node.requiredText("cache_target_path")),
          contentDigest = node.requiredText("content_digest"),
          sourceRoot = Path.of(node.requiredText("source_root")),
        )
      }?.toList() ?: error("entries is required")
      validateDecodedEntries(entries, home, managedRoots)
      decoded = entries
    } catch (error: CancellationException) {
      failure = error
    } catch (error: InvalidNativeAgentLinkInventorySchemaError) {
      failure = error
    } catch (error: IOException) {
      failure = InvalidNativeAgentLinkInventorySchemaError(
        "Invalid native-agent link inventory '$path': ${error.message}. Delete it and reinstall.",
        error,
      )
    } catch (error: IllegalArgumentException) {
      failure = InvalidNativeAgentLinkInventorySchemaError(
        "Invalid native-agent link inventory '$path': ${error.message}. Delete it and reinstall.",
        error,
      )
    } catch (error: IllegalStateException) {
      failure = InvalidNativeAgentLinkInventorySchemaError(
        "Invalid native-agent link inventory '$path': ${error.message}. Delete it and reinstall.",
        error,
      )
    }
    if (failure != null) throw failure
    return decoded!!
  }

  fun validateSemanticEntries(entries: List<NativeAgentLinkInventoryEntry>, home: Path, managedRoots: List<Path>) {
    require(entries.map { it.provider to it.installedPath.normalize() }.distinct().size == entries.size) {
      "duplicate provider/installed_path entry"
    }
    val logicalIdentities = entries.map {
      Triple(it.provider, it.installedPath.parent.normalize(), it.logicalName)
    }
    require(logicalIdentities.distinct().size == entries.size) {
      "duplicate provider/directory/logical_name entry"
    }
    entries.forEach { entry ->
      val provider = NativeAgentLinkInventoryPaths.provider(entry.provider)
      require(entry.sourceRoot.isAbsolute && entry.sourceRoot == entry.sourceRoot.normalize()) {
        "invalid source_root"
      }
      require(entry.installedPath.isAbsolute && entry.installedPath == entry.installedPath.normalize()) {
        "invalid installed_path"
      }
      require(entry.cacheTargetPath.isAbsolute && entry.cacheTargetPath == entry.cacheTargetPath.normalize()) {
        "invalid cache_target_path"
      }
      require(entry.installedPath.parent in provider.homeAgentDirs(home).map { it.toAbsolutePath().normalize() }) {
        "installed_path is outside provider directory"
      }
      require(entry.installedPath.fileName.toString() == provider.fileName(entry.logicalName)) {
        "invalid installed identity"
      }
      require(
        isCanonicalNativeAgentArtifactTarget(home, provider, entry.logicalName, entry.cacheTargetPath, managedRoots),
      ) {
        "cache_target_path does not match a trusted provider artifact"
      }
      require(entry.contentDigest.matches(Regex("[0-9a-f]{${NativeAgentLinkInventoryLimits.DIGEST_HEX_LENGTH}}"))) {
        "invalid content_digest"
      }
      require(isSemanticallyValid(entry, home, managedRoots)) { "installed artifact is not semantically valid" }
    }
  }

  fun isSemanticallyValid(entry: NativeAgentLinkInventoryEntry, home: Path, managedRoots: List<Path>): Boolean {
    return runCatching {
      val provider = NativeAgentLinkInventoryPaths.provider(entry.provider)
      val raw = Files.readSymbolicLink(entry.installedPath)
      val resolved = entry.installedPath.parent.resolve(raw).toAbsolutePath().normalize()
      entry.installedPath.fileName.toString() == provider.fileName(entry.logicalName) &&
        Files.isSymbolicLink(entry.installedPath) &&
        resolved == entry.cacheTargetPath &&
        isCanonicalNativeAgentArtifactTarget(home, provider, entry.logicalName, resolved, managedRoots) &&
        Files.isRegularFile(resolved) &&
        Files.isReadable(resolved) &&
        parseEmbeddedLogicalName(resolved, entry.provider) == entry.logicalName &&
        NativeAgentLinkInventoryPaths.sha256(Files.readAllBytes(resolved)) == entry.contentDigest
    }.getOrDefault(false)
  }

  private fun validateDecodedEntries(
    entries: List<NativeAgentLinkInventoryEntry>,
    home: Path,
    managedRoots: List<Path>,
  ) {
    require(entries.map { it.provider to it.installedPath.normalize() }.distinct().size == entries.size) {
      "duplicate provider/installed_path entry"
    }
    require(
      entries.groupBy { Triple(it.provider, it.installedPath.parent.normalize(), it.logicalName) }
        .values.none { it.size > 1 },
    ) { "duplicate provider/directory/logical_name entry" }
    entries.forEach { entry ->
      require(entry.provider in NativeAgentLinkInventoryLimits.PROVIDERS) { "unsupported provider '${entry.provider}'" }
      require(entry.contentDigest.matches(Regex("[0-9a-f]{${NativeAgentLinkInventoryLimits.DIGEST_HEX_LENGTH}}"))) {
        "invalid content_digest"
      }
      require(entry.installedPath.isAbsolute) { "installed_path must be absolute" }
      require(entry.cacheTargetPath.isAbsolute) { "cache_target_path must be absolute" }
      require(
        entry.sourceRoot.isAbsolute &&
          entry.sourceRoot.toString().length <= NativeAgentLinkInventoryLimits.MAX_SOURCE_ROOT_LENGTH,
      ) {
        "source_root must be an absolute bounded path"
      }
      require(entry.sourceRoot == entry.sourceRoot.normalize()) { "source_root must be normalized" }
      require(entry.installedPath == entry.installedPath.normalize()) { "installed_path must be normalized" }
      require(entry.cacheTargetPath == entry.cacheTargetPath.normalize()) { "cache_target_path must be normalized" }
      require(NativeAgentLinkInventoryLimits.LOGICAL_NAME.matches(entry.logicalName)) {
        "logical_name must be a single filename stem"
      }
      val provider = NativeAgentLinkInventoryPaths.provider(entry.provider)
      val allowedDirs = provider.homeAgentDirs(home).map { it.toAbsolutePath().normalize() }
      require(entry.installedPath.parent in allowedDirs) { "installed_path is outside provider directory" }
      require(entry.installedPath.fileName.toString() == provider.fileName(entry.logicalName)) {
        "installed_path does not match provider/logical_name identity"
      }
      require(
        isCanonicalNativeAgentArtifactTarget(home, provider, entry.logicalName, entry.cacheTargetPath, managedRoots),
      ) {
        "cache_target_path does not match a trusted provider artifact"
      }
    }
  }

  private fun parseEmbeddedLogicalName(path: Path, provider: String): String? {
    val text = Files.readString(path)
    val pattern = if (provider == "codex") {
      Regex("(?m)^name\\s*=\\s*\\\"([^\\\"]+)\\\"")
    } else {
      Regex("(?m)^name:\\s*['\\\"]?([^'\\\"\\r\\n]+)")
    }
    return pattern.find(text)?.groupValues?.get(1)?.trim()
  }

  private fun JsonNode.requiredText(field: String): String =
    get(field)?.asText()?.takeIf(String::isNotBlank) ?: error("$field is required")
}
