package skillbill.install.nativeagent

import skillbill.nativeagent.platformpack.NativeAgentDeclaredFiles
import skillbill.nativeagent.platformpack.NativeAgentGovernedAddonActivation
import skillbill.nativeagent.platformpack.NativeAgentGovernedAddonSelection
import skillbill.nativeagent.platformpack.NativeAgentGovernedAddonUsage
import skillbill.nativeagent.platformpack.NativeAgentPlatformPack
import skillbill.nativeagent.platformpack.NativeAgentPlatformPackLoader
import skillbill.nativeagent.platformpack.NativeAgentPointerSpec
import skillbill.scaffold.model.GovernedAddonActivation
import skillbill.scaffold.model.GovernedAddonSelection
import skillbill.scaffold.model.GovernedAddonUsage
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.PointerSpec
import java.nio.file.Path
import skillbill.scaffold.platformpack.discoverPlatformPackManifests as scaffoldDiscoverPlatformPackManifests
import skillbill.scaffold.platformpack.loadPlatformPack as scaffoldLoadPlatformPack

object InstallNativeAgentPlatformPackLoader : NativeAgentPlatformPackLoader {
  override fun loadPlatformPack(packRoot: Path): NativeAgentPlatformPack =
    scaffoldLoadPlatformPack(packRoot).toNativeAgentPlatformPack()

  override fun discoverPlatformPackManifests(platformPacksRoot: Path): List<NativeAgentPlatformPack> =
    scaffoldDiscoverPlatformPackManifests(platformPacksRoot).map(PlatformManifest::toNativeAgentPlatformPack)
}

fun PlatformManifest.toNativeAgentPlatformPack(): NativeAgentPlatformPack = NativeAgentPlatformPack(
  slug = slug,
  packRoot = packRoot,
  declaredFiles = NativeAgentDeclaredFiles(
    baseline = declaredFiles.baseline,
    areas = declaredFiles.areas,
  ),
  declaredQualityCheckFile = declaredQualityCheckFile,
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
