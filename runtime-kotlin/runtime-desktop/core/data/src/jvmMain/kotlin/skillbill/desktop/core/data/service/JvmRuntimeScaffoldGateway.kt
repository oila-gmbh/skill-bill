package skillbill.desktop.core.data.service

import kotlinx.coroutines.CancellationException
import me.tatarka.inject.annotations.Inject
import skillbill.desktop.core.common.di.UserScope
import skillbill.desktop.core.domain.model.PilotedPlatformPackEntry
import skillbill.desktop.core.domain.model.PlatformPackPresetEntry
import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.model.ScaffoldCatalogSnapshot
import skillbill.desktop.core.domain.model.ScaffoldOutcome
import skillbill.desktop.core.domain.model.ScaffoldPayload
import skillbill.desktop.core.domain.model.ScaffoldPlan
import skillbill.desktop.core.domain.model.ScaffoldRunResult
import skillbill.desktop.core.domain.service.RuntimeScaffoldGateway
import skillbill.error.ScaffoldRollbackError
import skillbill.error.SkillBillRuntimeException
import skillbill.scaffold.ScaffoldCatalog
import skillbill.scaffold.model.ScaffoldResult
import skillbill.scaffold.scaffold
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * JVM adapter around the runtime scaffolder. The [scaffolder] seam exists so unit tests can
 * substitute a deterministic stand-in without exercising the real on-disk runtime.
 *
 * The gateway catches every [SkillBillRuntimeException] subclass — both `ScaffoldError` family
 * (`InvalidScaffoldPayloadError`, `MissingPlatformPackError`, etc.) and shell-content-contract
 * subclasses (`MissingRequiredSectionError`, etc.) — because the runtime can surface either
 * family during planning/execution. The `rollbackComplete` flag is `true` for every runtime
 * exception except [ScaffoldRollbackError], which is the only subclass the runtime documents as
 * leaving the repo partially mutated.
 *
 * F-003/F-405: The defensive `catch (Exception)` below NEVER claims rollback completed, because
 * a non-runtime exception means the rollback machinery did not get to fire and we cannot
 * promise the repo is clean. CancellationException is re-thrown verbatim (kotlinx coroutine
 * contract) and JVM Errors (OOM, StackOverflow, LinkageError) are not caught at all so they can
 * propagate to the process supervisor.
 */
@Inject
@SingleIn(UserScope::class)
class JvmRuntimeScaffoldGateway : RuntimeScaffoldGateway {
  // F-107: scaffolder is a functional seam tests can swap to drive the runtime without exercising
  // the real on-disk scaffolder. Kept off the primary constructor so the public ABI of this
  // gateway does not leak `runtime-core` types into the umbrella module's KSP classpath — the
  // umbrella depends on `core:data` via `implementation`, which would otherwise force an
  // `api(:runtime-core)` leak to keep `ScaffoldResult` resolvable to KSP. See
  // RuntimeRepoBrowserService for the same pattern.
  internal var scaffolder: (Map<String, Any?>, Boolean) -> ScaffoldResult = ::scaffold

  override suspend fun catalogSnapshot(session: RepoSession?): ScaffoldCatalogSnapshot {
    val piloted = pilotedPlatformPacks(session)
    return ScaffoldCatalogSnapshot(
      approvedCodeReviewAreas = ScaffoldCatalog.approvedCodeReviewAreas.sorted(),
      preShellFamilies = ScaffoldCatalog.preShellFamilies.sorted(),
      shelledFamilies = ScaffoldCatalog.shelledFamilies.sorted(),
      platformPackPresets = ScaffoldCatalog.platformPackPresets
        .map { (slug, display) -> PlatformPackPresetEntry(platform = slug, displayName = display) }
        .sortedBy { it.platform },
      pilotedPlatformPacks = piloted,
      scaffoldPayloadVersion = ScaffoldCatalog.scaffoldPayloadVersion,
    )
  }

  private fun pilotedPlatformPacks(session: RepoSession?): List<PilotedPlatformPackEntry> {
    val repoPath = session?.takeIf { it.isRecognizedSkillBillRepo }?.repoPath?.trim().orEmpty()
    if (repoPath.isEmpty()) {
      return emptyList()
    }
    val packsRoot = try {
      Path.of(repoPath).toAbsolutePath().normalize().resolve("platform-packs")
    } catch (_: InvalidPathException) {
      return emptyList()
    }
    return try {
      ScaffoldCatalog.discoverPilotedPlatformPacks(packsRoot)
        .map { pack ->
          PilotedPlatformPackEntry(
            platform = pack.slug,
            displayName = pack.displayName ?: pack.slug,
          )
        }
        .sortedBy { it.platform }
    } catch (_: SkillBillRuntimeException) {
      emptyList()
    }
  }

  override suspend fun dryRun(payload: ScaffoldPayload): ScaffoldRunResult = invoke(payload, dryRun = true)

  override suspend fun execute(payload: ScaffoldPayload): ScaffoldRunResult = invoke(payload, dryRun = false)

  // F-004: distinguish Preview vs Success by the [dryRun] input flag the gateway already passes
  // in to the scaffolder, NOT by string-matching a note. Note-scanning silently flipped dry-run
  // to "Success" the moment the runtime changed the marker wording.
  // F-003/F-405: catch SkillBillRuntimeException for runtime-owned mapping; catch the broader
  // Exception branch defensively but mark `rollbackComplete = false` (cannot guarantee rollback
  // ran for non-runtime exceptions). CancellationException is re-thrown so coroutine cancellation
  // propagates verbatim. JVM Errors (OOM/StackOverflow/LinkageError) are NOT caught.
  @Suppress("TooGenericExceptionCaught")
  private fun invoke(payload: ScaffoldPayload, dryRun: Boolean): ScaffoldRunResult = try {
    val result = scaffolder(payload.toContractMap(), dryRun)
    if (dryRun) {
      ScaffoldRunResult.Preview(planned = result.toPlan())
    } else {
      ScaffoldRunResult.Success(result = result.toOutcome())
    }
  } catch (cancellation: CancellationException) {
    // kotlinx contract: never swallow CancellationException.
    throw cancellation
  } catch (error: SkillBillRuntimeException) {
    ScaffoldRunResult.Failed(
      exceptionName = error::class.simpleName.orEmpty(),
      exceptionMessage = error.message.orEmpty(),
      rollbackComplete = error !is ScaffoldRollbackError,
    )
  } catch (error: Exception) {
    // Defensive: a non-runtime exception would point at a programmer error in the gateway or in
    // the scaffolder. Surface it as a failed run so the UI does not crash, but we CANNOT claim
    // the repo is clean — the runtime rollback machinery only fires for SkillBillRuntimeException
    // subclasses. The UI surfaces the partial-mutation banner on `rollbackComplete = false`.
    ScaffoldRunResult.Failed(
      exceptionName = error::class.simpleName.orEmpty().ifBlank { "Exception" },
      exceptionMessage = error.message.orEmpty(),
      rollbackComplete = false,
    )
  }
}

private fun ScaffoldResult.toPlan(): ScaffoldPlan = ScaffoldPlan(
  kind = kind,
  skillName = skillName,
  skillPath = skillPath.toPortableString(),
  createdFiles = createdFiles.map(Path::toPortableString),
  manifestEdits = manifestEdits.map(Path::toPortableString),
  symlinks = symlinks.map(Path::toPortableString),
  installTargets = installTargets.map(Path::toPortableString),
  notes = notes,
)

private fun ScaffoldResult.toOutcome(): ScaffoldOutcome = ScaffoldOutcome(
  kind = kind,
  skillName = skillName,
  skillPath = skillPath.toPortableString(),
  createdFiles = createdFiles.map(Path::toPortableString),
  manifestEdits = manifestEdits.map(Path::toPortableString),
  symlinks = symlinks.map(Path::toPortableString),
  installTargets = installTargets.map(Path::toPortableString),
  notes = notes,
)

private fun Path.toPortableString(): String = toString().replace('\\', '/')
