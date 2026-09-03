package skillbill.cli.scaffold

import skillbill.application.install.ExternalAddonOverlayService
import skillbill.application.scaffold.InstallAgentService
import skillbill.application.scaffold.ScaffoldCatalogService
import skillbill.application.scaffold.ScaffoldService
import skillbill.application.scaffold.UnsupportedScaffoldService
import skillbill.cli.kernel.CliRunState
import skillbill.cli.model.CliFormat
import skillbill.cli.model.CliRunInputs
import java.time.Clock

internal data class NativeScaffoldRunArgs(
  val dryRun: Boolean,
  val format: CliFormat,
  val state: CliRunState,
  val inputs: CliRunInputs,
  val clock: Clock,
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
  val inputs: CliRunInputs,
  val clock: Clock,
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
  val inputs: CliRunInputs,
)
