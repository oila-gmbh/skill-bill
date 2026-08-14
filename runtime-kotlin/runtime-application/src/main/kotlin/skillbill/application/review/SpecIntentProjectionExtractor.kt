package skillbill.application.review

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.repoRelativePath
import skillbill.application.decomposition.resolvedParentSpecPath
import skillbill.domain.review.context.model.SpecIntentProjection
import skillbill.domain.review.context.model.SpecIntentProvenance
import skillbill.domain.review.context.model.SpecIntentSurroundingContext
import skillbill.error.InvalidReviewContextSchemaError
import skillbill.error.UnreadableSpecIntentProjectionError
import skillbill.review.context.ReviewContextEnvelopeValidator
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.spec.GovernedSpecSectionParser
import skillbill.review.spec.GovernedSpecSectionParser.ACCEPTANCE_CRITERIA_PREFIX
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

@Inject
class SpecIntentProjectionExtractor(
  private val envelopeValidator: ReviewContextEnvelopeValidator,
) {
  fun extract(
    repoRoot: Path,
    specPath: Path,
    budget: ReviewContextBudgetPolicy,
    surrounding: SpecIntentSurroundingContext? = null,
    explicit: Boolean,
  ): SpecIntentProjection {
    val normalized = resolvedParentSpecPath(repoRoot, specPath)
    val bytes = readSpecBytes(normalized, explicit)
    val specText = bytes.toString(Charsets.UTF_8)
    val intendedOutcome = GovernedSpecSectionParser.parseProseSection(specText) {
      it.startsWith(INTENDED_OUTCOME_PREFIX)
    }
    if (intendedOutcome.isBlank()) {
      fail(normalized, explicit, "unparseable")
    }
    val projection = SpecIntentProjection(
      intendedOutcome = intendedOutcome,
      acceptanceCriteria = GovernedSpecSectionParser.parseListSection(specText) {
        it.startsWith(ACCEPTANCE_CRITERIA_PREFIX)
      },
      constraints = GovernedSpecSectionParser.parseListSection(specText) { it.startsWith(CONSTRAINTS_PREFIX) },
      nonGoals = GovernedSpecSectionParser.parseListSection(specText) { title ->
        title.startsWith(NON_GOALS_PREFIX) || title == NON_GOALS_SPACED
      },
      deferredItems = GovernedSpecSectionParser.parseListSection(specText) { it.startsWith(DEFERRED_PREFIX) },
      provenance = SpecIntentProvenance(
        specPath = repoRelativePath(repoRoot, normalized),
        contentDigest = sha256Hex(bytes),
      ),
      declaredByteBudget = budget.maxSpecIntentProjectionBytes.toInt().coerceAtLeast(1),
      surroundingContext = surrounding,
    )
    enforceSpecIntentProjectionBudget(projection, budget)
    try {
      envelopeValidator.validateSpecIntentProjection(projection.toWireMap(), "spec_intent_projection")
    } catch (error: InvalidReviewContextSchemaError) {
      fail(normalized, explicit, "unparseable")
    }
    return projection
  }

  fun surroundingContext(repoRoot: Path, specPath: Path, explicit: Boolean): SpecIntentSurroundingContext {
    val normalized = resolvedParentSpecPath(repoRoot, specPath)
    val bytes = readSpecBytes(normalized, explicit)
    return SpecIntentSurroundingContext(
      specPath = repoRelativePath(repoRoot, normalized),
      contentDigest = sha256Hex(bytes),
    )
  }

  private fun readSpecBytes(path: Path, explicit: Boolean): ByteArray {
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      fail(path, explicit, if (Files.exists(path)) "unreadable" else "missing")
    }
    return try {
      Files.readAllBytes(path)
    } catch (_: Exception) {
      fail(path, explicit, "unreadable")
    }
  }

  private fun fail(path: Path, explicit: Boolean, reason: String): Nothing {
    if (explicit) {
      throw UnreadableSpecIntentProjectionError(path.toString(), reason)
    }
    throw SpecIntentSourceUnavailable(path.toString(), reason)
  }

  private companion object {
    const val INTENDED_OUTCOME_PREFIX = "intended outcome"
    const val CONSTRAINTS_PREFIX = "constraints"
    const val NON_GOALS_PREFIX = "non-goal"
    const val NON_GOALS_SPACED = "non goals"
    const val DEFERRED_PREFIX = "deferred"
  }
}

internal class SpecIntentSourceUnavailable(
  val specPath: String,
  val reason: String,
) : RuntimeException("Spec intent source '$specPath' is $reason")

private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
  .digest(bytes)
  .joinToString("") { byte -> "%02x".format(byte) }
