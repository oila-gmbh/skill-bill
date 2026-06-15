package skillbill.ports.featurespec

import skillbill.ports.featurespec.model.FeatureSpecPathResolveInput
import skillbill.ports.featurespec.model.FeatureSpecPathResolveResult

fun interface FeatureSpecPathResolverPort {
  fun resolve(input: FeatureSpecPathResolveInput): FeatureSpecPathResolveResult
}
