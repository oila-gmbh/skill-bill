package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.ports.scaffold.source.ScaffoldSourceLoaderPort
import skillbill.ports.scaffold.source.model.ScaffoldPlatformPackLoadRequest
import skillbill.ports.scaffold.source.model.ScaffoldPlatformPackLoadResult
import skillbill.scaffold.loadPlatformPack as fsLoadPlatformPack

/**
 * Filesystem adapter for [ScaffoldSourceLoaderPort]. Delegates to the existing
 * `skillbill.scaffold.loadPlatformPack` parse seam in `runtime-infra-fs`, which owns the
 * `platform.yaml` schema validation and on-disk file reading.
 */
@Inject
class FileSystemScaffoldSourceLoader : ScaffoldSourceLoaderPort {
  override fun loadPlatformPack(request: ScaffoldPlatformPackLoadRequest): ScaffoldPlatformPackLoadResult =
    ScaffoldPlatformPackLoadResult(
      packRoot = request.packRoot,
      manifest = fsLoadPlatformPack(request.packRoot),
    )
}
