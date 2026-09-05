package skillbill.install.nativeagent

import skillbill.error.InvalidNativeAgentLinkInventorySchemaError
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal fun reconcileNativeAgentLinkInventoryLocked(request: NativeAgentLinkInventoryLockedReconcileRequest) {
  val trustedRoots = NativeAgentLinkInventoryPaths.canonicalManagedCacheRoots(request.home, request.managedRoots)
  val previous = loadPreviousNativeAgentLinkInventory(
    request.path,
    request.home,
    trustedRoots,
    request.sourceRoot,
    request.beforeMutation,
  )
  val desiredPaths = request.desired.map { it.installedPath.normalize() }.toSet()
  previous.filter { it.provider == request.provider && it.installedPath.normalize() !in desiredPaths }
    .forEach { stale ->
      NativeAgentLinkInventoryBootstrap.removeIfStillManaged(stale, request.home, trustedRoots, request.beforeMutation)
    }
  val retained = previous.filter { it.provider != request.provider }.filter { entry ->
    if (NativeAgentLinkInventoryDecode.isSemanticallyValid(entry, request.home, trustedRoots)) {
      true
    } else {
      NativeAgentLinkInventoryBootstrap.removeIfStillManaged(entry, request.home, trustedRoots, request.beforeMutation)
      false
    }
  }
  var failure: Throwable? = null
  try {
    NativeAgentLinkInventoryWrite.write(
      NativeAgentLinkInventoryWriteRequest(
        path = request.path,
        entries = (retained + request.desired).sortedWith(compareBy({ it.provider }, { it.installedPath.toString() })),
        home = request.home,
        managedRoots = trustedRoots,
        mapper = request.mapper,
        schema = request.schema,
        beforeMutation = request.beforeMutation,
        afterTemporaryCreation = request.afterTemporaryCreation,
      ),
    )
  } catch (error: InvalidNativeAgentLinkInventorySchemaError) {
    failure = error
  } catch (error: IOException) {
    failure = InvalidNativeAgentLinkInventorySchemaError(
      "Invalid native-agent link inventory publication '${request.path}': ${error.message}",
      error,
    )
  } catch (error: IllegalArgumentException) {
    failure = InvalidNativeAgentLinkInventorySchemaError(
      "Invalid native-agent link inventory publication '${request.path}': ${error.message}",
      error,
    )
  } catch (error: IllegalStateException) {
    failure = InvalidNativeAgentLinkInventorySchemaError(
      "Invalid native-agent link inventory publication '${request.path}': ${error.message}",
      error,
    )
  }
  failure?.let { throw it }
}

private fun loadPreviousNativeAgentLinkInventory(
  path: Path,
  home: Path,
  trustedRoots: List<Path>,
  sourceRoot: Path,
  beforeMutation: (Path) -> Unit,
): List<NativeAgentLinkInventoryEntry> = if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
  NativeAgentLinkInventory.read(home, trustedRoots, sourceRoot)
} else {
  val bootstrap = NativeAgentLinkInventoryBootstrap.bootstrap(home, trustedRoots, sourceRoot)
  bootstrap.remove.forEach { remove ->
    NativeAgentLinkInventoryBootstrap.removeIfStillManaged(remove, home, trustedRoots, beforeMutation)
  }
  bootstrap.retain
}
