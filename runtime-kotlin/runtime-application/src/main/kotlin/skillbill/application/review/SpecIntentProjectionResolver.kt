package skillbill.application.review

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.repoRelativePath
import skillbill.domain.review.context.model.SpecIntentAbsenceReason
import skillbill.domain.review.context.model.SpecIntentDegradationRecord
import skillbill.domain.review.context.model.SpecIntentProjectionResolveRequest
import skillbill.domain.review.context.model.SpecIntentResolution
import skillbill.domain.review.context.model.SpecIntentResolutionRung
import skillbill.ports.workflow.DecompositionManifestFileStore
import skillbill.workflow.DecompositionManifestValidator
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.DecompositionManifestValidationResult
import skillbill.workflow.model.DecompositionSubtask
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger

internal val specIntentResolverLog: Logger =
  Logger.getLogger("skillbill.application.review.SpecIntentProjectionResolver")

@Inject
class SpecIntentProjectionResolver(
  private val fileStore: DecompositionManifestFileStore,
  private val validator: DecompositionManifestValidator,
  private val extractor: SpecIntentProjectionExtractor,
) {
  fun resolve(request: SpecIntentProjectionResolveRequest): SpecIntentResolution {
    val explicit = request.explicitSpecPath
    if (explicit != null) {
      return SpecIntentResolution.Resolved(
        extractor.extract(request.repoRoot, explicit, request.budget, surrounding = null, explicit = true),
      )
    }
    val issueKey = issueKeyFromBranch(request.branchName)
    val fromManifest = resolveManifest(request, issueKey)
    if (fromManifest != null) return fromManifest
    if (issueKey == null) {
      return none(SpecIntentAbsenceReason.NOT_APPLICABLE_SCOPE, SpecIntentResolutionRung.NONE)
    }
    return resolveGlob(request, issueKey)
  }

  private fun resolveManifest(
    request: SpecIntentProjectionResolveRequest,
    issueKey: String?,
  ): SpecIntentResolution? {
    val candidates = fileStore.findDecompositionManifestFiles(request.repoRoot)
    if (candidates.isEmpty()) return null
    val loaded = mutableListOf<Pair<Path, DecompositionManifest>>()
    candidates.forEach { path ->
      when (val manifest = readManifest(path)) {
        null -> emit(
          SpecIntentDegradationRecord(
            seam = MANIFEST_UNREADABLE_SEAM,
            reason = MANIFEST_UNREADABLE_REASON,
            rung = SpecIntentResolutionRung.GLOB.wireValue,
            resolvedPath = repoRelativePath(request.repoRoot, path),
          ),
        )
        else -> loaded += path to manifest
      }
    }
    val matching = loaded.map { it.second }.let { manifests ->
      issueKey?.let { key -> manifests.filter { it.issueKey == key } }.orEmpty().ifEmpty {
        manifests.filter { it.featureBranch == request.branchName }
      }
    }
    if (matching.size != 1) return null
    val manifest = matching.single()
    val owner = owningSubtask(manifest, request)
    val primary = Path.of(owner?.specPath ?: manifest.parentSpecPath)
    val surrounding = owner?.let {
      extractor.surroundingContext(request.repoRoot, Path.of(manifest.parentSpecPath), explicit = false)
    }
    return try {
      SpecIntentResolution.Resolved(
        extractor.extract(request.repoRoot, primary, request.budget, surrounding, explicit = false),
      )
    } catch (error: SpecIntentSourceUnavailable) {
      emit(
        SpecIntentDegradationRecord(
          seam = RESOLVE_SEAM,
          reason = SpecIntentAbsenceReason.NO_SPEC_FOUND.wireValue,
          rung = SpecIntentResolutionRung.MANIFEST.wireValue,
          resolvedPath = error.specPath,
        ),
      )
      null
    }
  }

  private fun resolveGlob(request: SpecIntentProjectionResolveRequest, issueKey: String): SpecIntentResolution {
    val featureSpecs = request.repoRoot.resolve(".feature-specs")
    if (!Files.isDirectory(featureSpecs)) {
      return none(SpecIntentAbsenceReason.NO_SPEC_FOUND, SpecIntentResolutionRung.GLOB)
    }
    val matches = Files.newDirectoryStream(featureSpecs, "$issueKey-*").use { stream ->
      stream.toList()
        .filter { Files.isDirectory(it) }
        .map { it.resolve("spec.md") }
        .filter { Files.isRegularFile(it) }
        .sorted()
    }
    return when (matches.size) {
      0 -> none(SpecIntentAbsenceReason.NO_SPEC_FOUND, SpecIntentResolutionRung.GLOB)
      1 -> try {
        SpecIntentResolution.Resolved(
          extractor.extract(request.repoRoot, matches.single(), request.budget, surrounding = null, explicit = false),
        )
      } catch (_: SpecIntentSourceUnavailable) {
        none(SpecIntentAbsenceReason.NO_SPEC_FOUND, SpecIntentResolutionRung.GLOB)
      }
      else -> none(SpecIntentAbsenceReason.AMBIGUOUS_MATCH, SpecIntentResolutionRung.GLOB)
    }
  }

  private fun readManifest(path: Path): DecompositionManifest? {
    return try {
      if (!fileStore.isRegularFile(path)) return null
      when (val result = validator.validateYamlTextResult(fileStore.readText(path), path.toString())) {
        is DecompositionManifestValidationResult.AcceptedUnchanged -> result.manifest
        else -> null
      }
    } catch (_: Exception) {
      null
    }
  }

  private fun owningSubtask(
    manifest: DecompositionManifest,
    request: SpecIntentProjectionResolveRequest,
  ): DecompositionSubtask? {
    val byIntent = manifest.subtasks.firstOrNull { it.id == manifest.currentSubtaskIntent.subtaskId }
    if (byIntent != null) return byIntent
    val byBranch = manifest.subtasks.filter { it.branch == request.branchName }
    if (byBranch.size == 1) return byBranch.single()
    val byPath = manifest.subtasks.filter { subtask ->
      request.changedPaths.any { changed -> changed == subtask.specPath || changed.startsWith(prefix(subtask.specPath)) }
    }
    return byPath.singleOrNull()
  }

  private fun none(reason: SpecIntentAbsenceReason, rung: SpecIntentResolutionRung): SpecIntentResolution {
    emit(
      SpecIntentDegradationRecord(
        seam = RESOLVE_SEAM,
        reason = reason.wireValue,
        rung = rung.wireValue,
      ),
    )
    return SpecIntentResolution.None(reason)
  }

  private fun emit(record: SpecIntentDegradationRecord) {
    specIntentResolverLog.warning(
      "spec intent resolution degraded: seam=${record.seam} reason=${record.reason} " +
        "rung=${record.rung} resolved_path=${record.resolvedPath ?: "-"}",
    )
  }

  private companion object {
    const val RESOLVE_SEAM = "SpecIntentProjectionResolver.resolve"
    const val MANIFEST_UNREADABLE_SEAM = "SpecIntentProjectionResolver.manifest_unreadable"
    const val MANIFEST_UNREADABLE_REASON = "manifest_unreadable"
    val ISSUE_KEY_IN_BRANCH = Regex("""[A-Z][A-Z0-9]*-[0-9]+""")

    fun issueKeyFromBranch(branchName: String): String? =
      ISSUE_KEY_IN_BRANCH.find(branchName)?.value

    fun prefix(specPath: String): String {
      val directory = specPath.substringBeforeLast('/', "")
      return if (directory.isEmpty()) specPath else "$directory/"
    }
  }
}
