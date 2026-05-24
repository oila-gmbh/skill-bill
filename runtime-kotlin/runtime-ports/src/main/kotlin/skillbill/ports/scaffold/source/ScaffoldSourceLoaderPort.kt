package skillbill.ports.scaffold.source

import skillbill.ports.scaffold.source.model.ScaffoldPlatformPackLoadRequest
import skillbill.ports.scaffold.source.model.ScaffoldPlatformPackLoadResult

/**
 * Capability port for scaffold-time source loading. Implementations parse the on-disk
 * `platform.yaml` for a given platform-pack root and return the typed [skillbill.scaffold.model.PlatformManifest].
 *
 * IO ownership stays in the adapter (`runtime-infra-fs`). Pure-policy callers must depend on this
 * port instead of touching the filesystem directly.
 */
fun interface ScaffoldSourceLoaderPort {
  fun loadPlatformPack(request: ScaffoldPlatformPackLoadRequest): ScaffoldPlatformPackLoadResult
}
