package skillbill.application.featurespec

import me.tatarka.inject.annotations.Inject
import skillbill.featurespec.FeatureSpecPreparationPolicy
import skillbill.featurespec.model.FeatureSpecPreparationDecision
import skillbill.featurespec.model.FeatureSpecPreparationIntake

/**
 * Shared feature-spec preparation entry points for orchestrators.
 *
 * The feature-spec, feature, and goal wrappers intentionally call the same
 * injected core to prevent divergence.
 */
@Inject
class FeatureSpecPreparationRuntime(
  private val prepareCore: (FeatureSpecPreparationIntake) -> FeatureSpecPreparationDecision,
) {
  fun prepareForFeatureSpec(intake: FeatureSpecPreparationIntake): FeatureSpecPreparationDecision = prepareCore(intake)

  fun prepareForFeatureImplement(intake: FeatureSpecPreparationIntake): FeatureSpecPreparationDecision =
    prepareCore(intake)

  fun prepareForGoal(intake: FeatureSpecPreparationIntake): FeatureSpecPreparationDecision = prepareCore(intake)
}
