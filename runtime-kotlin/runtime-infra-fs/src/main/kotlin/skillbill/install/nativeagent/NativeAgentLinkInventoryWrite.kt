package skillbill.install.nativeagent

import skillbill.contracts.nativeagent.NATIVE_AGENT_LINK_INVENTORY_CONTRACT_VERSION
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.coroutines.cancellation.CancellationException

internal object NativeAgentLinkInventoryWrite {
  fun write(request: NativeAgentLinkInventoryWriteRequest) {
    request.path.parent?.let { parent ->
      journalMissingAncestors(parent, request.beforeMutation)
      Files.createDirectories(parent)
    }
    val root = request.mapper.createObjectNode().put("contract_version", NATIVE_AGENT_LINK_INVENTORY_CONTRACT_VERSION)
    val array = root.putArray("entries")
    request.entries.forEach { entry ->
      array.addObject()
        .put("logical_name", entry.logicalName)
        .put("provider", entry.provider)
        .put("installed_path", entry.installedPath.toAbsolutePath().normalize().toString())
        .put("cache_target_path", entry.cacheTargetPath.toAbsolutePath().normalize().toString())
        .put("content_digest", entry.contentDigest)
        .put("source_root", entry.sourceRoot.toAbsolutePath().normalize().toString())
    }
    val bytes = request.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root)
    require(bytes.size <= NativeAgentLinkInventoryLimits.MAX_BYTES) {
      "native-agent link inventory exceeds ${NativeAgentLinkInventoryLimits.MAX_BYTES} bytes"
    }
    val schemaErrors = request.schema.validate(request.mapper.readTree(bytes))
    require(schemaErrors.isEmpty()) { schemaErrors.joinToString("; ") { it.message } }
    NativeAgentLinkInventoryDecode.validateSemanticEntries(request.entries, request.home, request.managedRoots)
    val temporary = Files.createTempFile(request.path.parent, "${request.path.fileName}.", ".tmp")
    request.afterTemporaryCreation(temporary)
    var initiatingFailure: Throwable? = null
    try {
      Files.write(temporary, bytes)
      request.beforeMutation(request.path)
      try {
        Files.move(temporary, request.path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary, request.path, StandardCopyOption.REPLACE_EXISTING)
      }
    } catch (error: CancellationException) {
      throw error
    } catch (error: IOException) {
      initiatingFailure = error
    } catch (error: IllegalArgumentException) {
      initiatingFailure = error
    } catch (error: IllegalStateException) {
      initiatingFailure = error
    }
    val cleanupFailure = runCatching { Files.deleteIfExists(temporary) }.exceptionOrNull()
    cleanupFailure?.let { initiatingFailure?.addSuppressed(it) }
    val terminalFailure = initiatingFailure ?: cleanupFailure
    terminalFailure?.let { throw it }
  }

  private fun journalMissingAncestors(path: Path, beforeMutation: (Path) -> Unit) {
    val missing = generateSequence(path.toAbsolutePath().normalize()) { it.parent }
      .takeWhile { !Files.exists(it, LinkOption.NOFOLLOW_LINKS) }.toList().asReversed()
    missing.forEach(beforeMutation)
  }
}
