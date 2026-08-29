package skillbill.cli.scaffold

import skillbill.application.install.ExternalAddonOverlayService
import skillbill.application.scaffold.InstallAgentService
import skillbill.application.scaffold.ScaffoldCatalogService
import skillbill.application.scaffold.ScaffoldService
import skillbill.application.scaffold.UnsupportedScaffoldService
import skillbill.cli.core.CliRunState
import skillbill.cli.model.CliFormat

internal data class NativeScaffoldRunArgs(
  val dryRun: Boolean,
  val format: CliFormat,
  val state: CliRunState,
  val scaffoldService: ScaffoldService,
  val externalAddonOverlayService: ExternalAddonOverlayService? = null,
)

internal data class AssistedScaffoldWizardArgs(
  val run: NativeScaffoldRunArgs,
  val scaffoldCatalogService: ScaffoldCatalogService,
  val installAgentService: InstallAgentService,
)

internal data class ScaffoldWizardArgs(
  val run: NativeScaffoldRunArgs,
  val scaffoldCatalogService: ScaffoldCatalogService,
)

internal data class NativeScaffoldPayloadPathArgs(
  val payloadPath: String?,
  val run: NativeScaffoldRunArgs,
  val transform: (Map<String, *>) -> Map<String, *> = { it },
)

internal data class CreateAndFillContentArgs(
  val payload: String?,
  val interactive: Boolean,
  val body: String?,
  val bodyFile: String?,
  val editor: Boolean,
)

internal data class CreateAndFillArgs(
  val content: CreateAndFillContentArgs,
  val dryRun: Boolean,
  val format: CliFormat,
  val state: CliRunState,
  val scaffoldService: ScaffoldService,
  val unsupportedScaffoldService: UnsupportedScaffoldService,
)

internal data class NewAddonPayloadArgs(
  val platform: String?,
  val name: String?,
  val body: String?,
  val bodyFile: String?,
  val addonLocationPath: String?,
  val consumerSkillDirs: List<String>,
  val state: CliRunState,
)

internal fun nativeScaffoldRunArgs(
  dryRun: Boolean,
  format: CliFormat,
  state: CliRunState,
  scaffoldService: ScaffoldService,
  externalAddonOverlayService: ExternalAddonOverlayService? = null,
) = NativeScaffoldRunArgs(
  dryRun = dryRun,
  format = format,
  state = state,
  scaffoldService = scaffoldService,
  externalAddonOverlayService = externalAddonOverlayService,
)
