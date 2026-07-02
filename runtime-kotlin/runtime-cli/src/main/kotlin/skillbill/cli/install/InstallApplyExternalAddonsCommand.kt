package skillbill.cli.install

import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import me.tatarka.inject.annotations.Inject
import skillbill.application.install.ExternalAddonOverlayService
import skillbill.cli.core.CliRunState
import skillbill.cli.core.DocumentedCliCommand
import skillbill.error.ShellContentContractException
import java.nio.file.Path

@Inject
class InstallApplyExternalAddonsCommand(
  private val state: CliRunState,
  private val service: ExternalAddonOverlayService,
) : DocumentedCliCommand(
  "apply-external-addons",
  "Apply the external addon overlay onto installed platform packs (after reconcile, before staging).",
) {
  private val repoRoot by option(
    "--repo-root",
    help = "Repository root containing platform-packs/. Defaults to the current working directory.",
  ).default(".")
  private val platformPacksRoot by option(
    "--platform-packs",
    help = "Platform packs root. Defaults to <repo-root>/platform-packs.",
  )

  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation("apply-external-addons")) {
      return
    }
    val resolvedRepoRoot = Path.of(repoRoot).toAbsolutePath().normalize()
    val resolvedPlatformPacks = platformPacksRoot?.let(Path::of)?.toAbsolutePath()?.normalize()
      ?: resolvedRepoRoot.resolve("platform-packs")
    val result = try {
      service.applyOverlay(resolvedPlatformPacks, state.userHome, state.environment)
    } catch (error: ShellContentContractException) {
      state.completeText(
        "${error.message}\n",
        mapOf("status" to "failed", "error" to error.message.orEmpty()),
        exitCode = 1,
      )
      return
    }
    if (result.appliedSources.isEmpty() && result.skippedSources.isEmpty()) {
      state.completeText(
        "no external addon sources\n",
        mapOf("status" to "ok", "touched" to false),
      )
      return
    }
    val applied = result.appliedSources.joinToString("") { source ->
      "applied\t${source.platform}\t${source.sourcePath}\n"
    }
    val skipped = result.skippedSources.joinToString("") { source ->
      "skipped\t${source.platform}\t${source.sourcePath}\t${source.reason}\n"
    }
    state.completeText(
      applied + skipped,
      mapOf(
        "status" to "ok",
        "touched" to result.touched,
        "applied" to result.appliedSources.map { it.platform },
        "skipped" to result.skippedSources.map { it.platform },
      ),
    )
  }
}
