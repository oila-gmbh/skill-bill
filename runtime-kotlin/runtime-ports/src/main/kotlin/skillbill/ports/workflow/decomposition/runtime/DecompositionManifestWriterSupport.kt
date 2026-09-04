package skillbill.ports.workflow.decomposition.runtime
import java.nio.file.Path

fun resolvedParentSpecPath(repoRoot: Path, parentSpecPath: Path): Path =
  if (parentSpecPath.isAbsolute) parentSpecPath.normalize()
  else repoRoot.resolve(parentSpecPath).normalize()

fun Any?.asStringAnyMapOrNull(): Map<String, Any?>? =
  (this as? Map<*, *>)?.entries?.associateTo(LinkedHashMap()) { (key, value) ->
    val stringKey = key as? String ?: return null
    stringKey to value
  }
