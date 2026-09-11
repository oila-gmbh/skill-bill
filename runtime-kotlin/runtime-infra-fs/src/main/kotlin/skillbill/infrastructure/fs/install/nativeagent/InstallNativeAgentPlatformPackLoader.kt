package skillbill.infrastructure.fs.install.nativeagent

import skillbill.infrastructure.fs.nativeagent.platformpack.NativeAgentDeclaredFiles
import skillbill.infrastructure.fs.nativeagent.platformpack.NativeAgentGovernedAddonActivation
import skillbill.infrastructure.fs.nativeagent.platformpack.NativeAgentGovernedAddonSelection
import skillbill.infrastructure.fs.nativeagent.platformpack.NativeAgentGovernedAddonUsage
import skillbill.infrastructure.fs.nativeagent.platformpack.NativeAgentPlatformPack
import skillbill.infrastructure.fs.nativeagent.platformpack.NativeAgentPlatformPackLoader
import skillbill.infrastructure.fs.nativeagent.platformpack.NativeAgentPointerSpec
import skillbill.model.toPath
import skillbill.scaffold.model.GovernedAddonActivation
import skillbill.scaffold.model.GovernedAddonSelection
import skillbill.scaffold.model.GovernedAddonUsage
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.PointerSpec
import java.nio.file.Path
import skillbill.infrastructure.fs.scaffold.platformpack.discoverPlatformPackManifests as scaffoldDiscoverPlatformPackManifests
import skillbill.infrastructure.fs.scaffold.platformpack.loadPlatformPack as scaffoldLoadPlatformPack

object InstallNativeAgentPlatformPackLoader : NativeAgentPlatformPackLoader {
  override fun loadPlatformPack(packRoot: Path): NativeAgentPlatformPack =
    scaffoldLoadPlatformPack(packRoot).toNativeAgentPlatformPack()

  override fun discoverPlatformPackManifests(platformPacksRoot: Path): List<NativeAgentPlatformPack> =
    scaffoldDiscoverPlatformPackManifests(platformPacksRoot).map(PlatformManifest::toNativeAgentPlatformPack)
}

fun PlatformManifest.toNativeAgentPlatformPack(): NativeAgentPlatformPack = NativeAgentPlatformPack(
  slug = slug,
  packRoot = packRoot.toPath(),
  declaredFiles = NativeAgentDeclaredFiles(
    baseline = declaredFiles.baseline?.toPath(),
    areas = declaredFiles.areas.mapValues { (_, entry) -> entry.toPath() },
  ),
  declaredQualityCheckFile = declaredQualityCheckFile?.toPath(),
  pointers = pointers.map(PointerSpec::toNativeAgentPointerSpec),
  addonUsage = addonUsage.map(GovernedAddonUsage::toNativeAgentGovernedAddonUsage),
)

private fun PointerSpec.toNativeAgentPointerSpec(): NativeAgentPointerSpec = NativeAgentPointerSpec(
  skillRelativeDir = skillRelativeDir,
  name = name,
  target = target,
)

private fun GovernedAddonUsage.toNativeAgentGovernedAddonUsage(): NativeAgentGovernedAddonUsage =
  NativeAgentGovernedAddonUsage(
    skillRelativeDir = skillRelativeDir,
    addons = addons.map(GovernedAddonSelection::toNativeAgentGovernedAddonSelection),
  )

private fun GovernedAddonSelection.toNativeAgentGovernedAddonSelection(): NativeAgentGovernedAddonSelection =
  NativeAgentGovernedAddonSelection(
    slug = slug,
    entrypoint = entrypoint,
    companionPointers = companionPointers,
    activation = activation?.toNativeAgentGovernedAddonActivation(),
    specialistAreas = specialistAreas,
  )

private fun GovernedAddonActivation.toNativeAgentGovernedAddonActivation(): NativeAgentGovernedAddonActivation =
  NativeAgentGovernedAddonActivation(
    anyPath = anyPath,
    anyContent = anyContent,
    allContent = allContent,
    anyOfAllContent = anyOfAllContent,
    excludePath = excludePath,
    excludeContent = excludeContent,
  )
