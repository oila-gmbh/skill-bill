package skillbill.contracts.workflow

import com.fasterxml.jackson.databind.JsonNode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFailureCode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFormat
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairOperation

internal object StructuralRepairCandidateEngine {
  private const val MAX_CANDIDATES = 8

  fun repairExactText(
    text: String,
    sourceLabel: String,
    sourceOffset: Int = 0,
    sourceText: String = text,
  ): FeatureTaskRuntimePhaseOutputStructuralRepairDecision? {
    val generation = collectCandidates(text)
    return when {
      generation.limitExceeded -> StructuralRepairDecisions.reject(
        FeatureTaskRuntimePhaseOutputFailureCode.REPAIR_LIMIT_EXCEEDED,
        "Phase output exceeded the bounded structural-repair candidate limit.",
      )
      generation.candidates.isNotEmpty() -> evaluateCandidates(
        generation.candidates,
        text,
        sourceLabel,
        sourceOffset,
        sourceText,
      )
      generation.unsupportedYaml -> StructuralRepairDecisions.reject(
        FeatureTaskRuntimePhaseOutputFailureCode.UNSUPPORTED_REPAIR,
        "YAML structural repair is limited to conservative flow structure.",
      )
      else -> null
    }
  }

  private fun collectCandidates(text: String): CandidateGeneration {
    val formats = StrictPhaseOutputParser.formatsFor(text)
    val generatedByFormat = formats.associateWith { format ->
      StructuralRepairSyntax.generateCandidates(text, format, MAX_CANDIDATES)
    }
    val candidates = generatedByFormat.flatMap { (format, generated) ->
      generated.take(MAX_CANDIDATES).map { candidate -> candidate.copy(format = format) }
    }.distinctBy { it.format to it.text }
    val unsupportedYaml =
      formats.singleOrNull() == FeatureTaskRuntimePhaseOutputFormat.YAML &&
        generatedByFormat[FeatureTaskRuntimePhaseOutputFormat.YAML].isNullOrEmpty() &&
        !StructuralRepairSyntax.isConservativeYamlFlow(text)
    return CandidateGeneration(
      candidates = candidates,
      limitExceeded = formats.any { format ->
        StructuralRepairSyntax.exceedsCandidateLimit(text, format, MAX_CANDIDATES)
      } || generatedByFormat.values.any { it.size > MAX_CANDIDATES },
      unsupportedYaml = unsupportedYaml,
    )
  }

  internal fun evaluateCandidates(
    candidates: List<Candidate>,
    originalText: String,
    sourceLabel: String,
    sourceOffset: Int,
    sourceText: String,
  ): FeatureTaskRuntimePhaseOutputStructuralRepairDecision {
    val considered = candidates.mapNotNull { candidate ->
      when (val result = StrictPhaseOutputParser.parseStrict(candidate.text, candidate.format)) {
        is StrictParse.Success -> Triple(candidate, result.node, false)
        is StrictParse.Failure -> if (result.code == FeatureTaskRuntimePhaseOutputFailureCode.DUPLICATE_KEY) {
          DuplicateKeyMergeParser.merge(candidate.text, candidate.format)?.let { merged ->
            Triple(
              candidate.copy(text = merged.repairedText, changedOffset = merged.firstDuplicateOffset),
              merged.node,
              true,
            )
          }
        } else {
          null
        }
      }
    }
    return when {
      considered.isEmpty() -> StructuralRepairDecisions.reject(
        FeatureTaskRuntimePhaseOutputFailureCode.NO_REPAIR_CANDIDATE,
        "Phase output is malformed and no bounded structural-repair candidate parses strictly.",
      )
      considered.size != 1 -> StructuralRepairDecisions.reject(
        FeatureTaskRuntimePhaseOutputFailureCode.AMBIGUOUS_REPAIR,
        "Phase output has multiple strictly parseable structural-repair candidates.",
      )
      else -> {
        val (candidate, node, mergedDuplicateKeys) = considered.single()
        acceptCandidate(
          candidate,
          node,
          mergedDuplicateKeys,
          StructuralRepairOrigin(originalText, sourceLabel, sourceOffset, sourceText),
        )
      }
    }
  }

  private data class StructuralRepairOrigin(
    val originalText: String,
    val sourceLabel: String,
    val sourceOffset: Int,
    val sourceText: String,
  )

  private fun acceptCandidate(
    candidate: Candidate,
    node: JsonNode,
    mergedDuplicateKeys: Boolean,
    origin: StructuralRepairOrigin,
  ): FeatureTaskRuntimePhaseOutputStructuralRepairDecision {
    return if (!node.isObject) {
      StructuralRepairDecisions.reject(
        FeatureTaskRuntimePhaseOutputFailureCode.ROOT_NOT_OBJECT,
        "<root> must be an object after structural repair.",
      )
    } else {
      val operation = when {
        mergedDuplicateKeys -> FeatureTaskRuntimePhaseOutputRepairOperation.DEDUPLICATE_KEYS
        candidate.text.length < origin.originalText.length ->
          FeatureTaskRuntimePhaseOutputRepairOperation.REMOVE_EXTRA_CLOSING_DELIMITER
        else -> FeatureTaskRuntimePhaseOutputRepairOperation.ADD_MISSING_CLOSING_DELIMITER
      }
      val evidence = FeatureTaskRuntimePhaseOutputRepairEvidence(
        format = if (mergedDuplicateKeys) FeatureTaskRuntimePhaseOutputFormat.JSON else candidate.format,
        originalDigest = StructuralRepairSyntax.sha256(origin.originalText),
        repairedDigest = StructuralRepairSyntax.sha256(candidate.text),
        operation = operation,
        sourceLocation = StructuralRepairSyntax.sourceLocation(
          origin.sourceLabel,
          origin.sourceText,
          origin.sourceOffset + candidate.changedOffset,
        ),
      )
      StructuralRepairDecisions.accepted(candidate.text, node, evidence)
    }
  }
}
