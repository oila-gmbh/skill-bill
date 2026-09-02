#!/usr/bin/env python3
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / (
    "runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask"
)

LAUNCH_NESTED = (
    "LaunchCaptureBeforeResult",
    "LaunchCaptureBeforeState",
    "LaunchCaptureAfterResult",
)


def fix_lambda_state_param(text: str) -> str:
    text = text.replace("{ runLoop.state ->", "{ state ->")
    text = re.sub(
        r"(updateReviewState\([^)]+\) \{ state ->\n)([\s\S]*?)(\n\s*\})",
        lambda m: m.group(1)
        + re.sub(r"runLoop\.state\.", "state.", m.group(2))
        + m.group(3),
        text,
    )
    return text


def fix_launch_nested_types(text: str, path: Path) -> str:
    if "FeatureTaskRuntimeRunLoopLaunch" not in path.name:
        return text
    if path.name == "FeatureTaskRuntimeRunLoopLaunch.kt":
        return text
    for name in LAUNCH_NESTED:
        text = re.sub(
            rf"(?<![.\w]){name}\b",
            f"FeatureTaskRuntimeRunLoopLaunch.{name}",
            text,
        )
    return text


def fix_file(path: Path) -> bool:
    original = path.read_text()
    text = original

    text = fix_lambda_state_param(text)
    text = text.replace("val runLoop.session.", "runLoop.session.")
    text = re.sub(r"\brunLoop, runLoop:", "runLoop:", text)
    text = re.sub(
        r"FeatureTaskRuntimeAuditRepairProgressDecision\(runLoop\.session\.blocked = false",
        "FeatureTaskRuntimeAuditRepairProgressDecision(blocked = false",
        text,
    )
    text = re.sub(
        r"implementationContinuationFor\(runLoop, runLoop:",
        "implementationContinuationFor(runLoop:",
        text,
    )
    text = re.sub(
        r"(?<![.\w])gitOperations\.",
        "runLoop.gitOperations.",
        text,
    )
    text = re.sub(r"reopenedSpan\.forEach\(state::", "reopenedSpan.forEach(runLoop.state::", text)
    text = fix_launch_nested_types(text, path)

    if path.name == "FeatureTaskRuntimeRunLoopCollaborators.kt":
        text = text.replace("@Inject\nclass FeatureTaskRuntimeRunLoopCollaborators", "@Inject\ninternal class FeatureTaskRuntimeRunLoopCollaborators")

    if path.name == "FeatureTaskRuntimeRunLoop.kt":
        text = text.replace(
            "collaborators.drive.blockAt(this, phaseId, reason)",
            "collaborators.planningBranch.blockAt(this, phaseId, reason)",
        )
        text = text.replace(
            "collaborators.drive.applyAuditGapPauseDecision(this, auditGapPause, decision)",
            "collaborators.planningBranch.applyAuditGapPauseDecision(this, auditGapPause, decision)",
        )

    if path.name == "FeatureTaskRuntimeRunLoopAttemptSettlement.kt":
        text = text.replace(
            "settleValidatedOutputBoundary(capture, outputMap, ::reject)",
            "settleValidatedOutputBoundary(runLoop, capture, outputMap, ::reject)",
        )

    if path.name == "FeatureTaskRuntimeRunLoopDriveContinued2.kt":
        text = text.replace(
            "edge?.let(::resumeInFlightReviewFix)?.let { return it }",
            "edge?.let { runLoop.collaborators.backwardEdge.resumeInFlightReviewFix(runLoop, it) }?.let { return it }",
        )
        text = text.replace(
            "runLoop.collaborators.phaseRunner.settleCarriedForwardGoalReview(runLoop,",
            "settleCarriedForwardGoalReview(runLoop,",
        )

    if path.name == "FeatureTaskRuntimeRunLoopDriveContinued3.kt":
        text = text.replace("val settled = advance(phaseId)", "val settled = runLoop.advance(phaseId)")

    if path.name == "FeatureTaskRuntimeRunLoopPhaseRunner.kt":
        text = text.replace("durablyClosedCriterionRefs(runLoop, runLoop)", "durablyClosedCriterionRefs(runLoop)")
        text = text.replace("declaredCriterionRefs(runLoop, runLoop)", "declaredCriterionRefs(runLoop)")

    if path.name == "FeatureTaskRuntimeRunLoopTransitions.kt":
        text = text.replace(
            "spanBetween(destinationPhaseId, edge.fromPhaseId)",
            "spanBetween(runLoop, destinationPhaseId, edge.fromPhaseId)",
        )
        text = text.replace(
            "blockedReason = ::auditReviewCheckpointBlockedReason,",
            "blockedReason = { branch, error -> runLoop.collaborators.planningBranch.auditReviewCheckpointBlockedReason(runLoop, branch, error) },",
        )

    if path.name == "FeatureTaskRuntimeRunLoopValidationGate.kt":
        text = text.replace(
            "acceptRuntimeOwnedBuild(run, outputText)",
            "acceptRuntimeOwnedBuild(runLoop, run, outputText)",
        )
        text = text.replace(
            "return persistRuntimeOwnedBuildCompletion(run, iteration, outputText, observability, acceptedOutput)",
            "return persistRuntimeOwnedBuildCompletion(runLoop, run, iteration, outputText, observability, acceptedOutput)",
        )

    if path.name == "FeatureTaskRuntimeRunLoopLaunchContinued3.kt":
        for fn in (
            "rejectedHandoffLaunch",
            "rejectedBriefingLaunch",
            "rejectedPlanningProjectionLaunch",
            "rejectedDurableBriefingLaunch",
        ):
            text = re.sub(
                rf"(?<![.\w]){fn}\(\s*\n?\s*run,",
                f"{fn}(runLoop, run,",
                text,
            )
            text = re.sub(rf"(?<![.\w]){fn}\(run,", f"{fn}(runLoop, run,", text)

    if path.name == "FeatureTaskRuntimeRunLoopLaunchContinued2.kt":
        text = text.replace("outputEnvelopeOf(output)", "outputEnvelopeOf(runLoop, output)")

    if path.name == "FeatureTaskRuntimeRunLoopOutputPersistenceContinued2.kt":
        text = re.sub(
            r"(?<![.\w])assembleLaunchHandoff\(\s*\n?\s*run,",
            "assembleLaunchHandoff(runLoop, run,",
            text,
        )
        text = re.sub(
            r"(?<![.\w])composeLaunchPrompt\(run,",
            "composeLaunchPrompt(runLoop, run,",
            text,
        )

    if path.name == "FeatureTaskRuntimeRunLoopOutputVerificationContinued3.kt":
        text = text.replace(
            "headRevision = resolvedBranch?.branch?.takeIf(String::isNotBlank) ?: \"HEAD\",",
            "headRevision = runLoop.session.resolvedBranch?.branch?.takeIf(String::isNotBlank) ?: \"HEAD\",",
        )

    if path.name == "FeatureTaskRuntimeRunLoopReview.kt":
        text = text.replace(
            "resolveReviewRunId(state.recordFor(run.phaseId), passNumber)",
            "resolveReviewRunId(runLoop, state.recordFor(run.phaseId), passNumber)",
        )
        text = text.replace(
            "runtimeOwnedReviewDriverRequest(run, input, passNumber, pinnedMode, reviewRunId)",
            "runtimeOwnedReviewDriverRequest(runLoop, run, input, passNumber, pinnedMode, reviewRunId)",
        )
        text = text.replace(
            "runLoop.phaseGates.reviewDriver.run(runLoop.request)",
            "runLoop.phaseGates.reviewDriver.run(request)",
        )

    if path.name == "FeatureTaskRuntimeRunLoopReviewContinued1.kt":
        text = text.replace(
            "retainRuntimeOwnedReviewEvidence(run, runLoop.state, iteration, outputText)",
            "retainRuntimeOwnedReviewEvidence(runLoop, run, runLoop.state, iteration, outputText)",
        )
        text = text.replace(
            "persistReviewCompletionOutcome(\n      PhaseReviewCompletionOutcomeArgs(",
            "persistReviewCompletionOutcome(runLoop, PhaseReviewCompletionOutcomeArgs(",
        )
        text = text.replace(
            "return completeRuntimeOwnedReviewPhase(\n      run,\n      iteration,\n      observability,\n      acceptedOutput.normalizedOutput,\n      acceptedOutput,\n    )",
            "return completeRuntimeOwnedReviewPhase(runLoop, run, iteration, observability, acceptedOutput.normalizedOutput, acceptedOutput)",
        )

    if path.name == "FeatureTaskRuntimeRunLoopPhaseAttemptsContinued3.kt":
        text = text.replace(
            "missingProducerAgentBlock(run, iteration, consumer, producer, runLoop.observability)",
            "missingProducerAgentBlock(runLoop, run, iteration, consumer, producer, runLoop.observability)",
        )
        text = re.sub(
            r"missingProducerEvidenceBlock\(\s*\n?\s*MissingProducerEvidenceBlockArgs\(",
            "missingProducerEvidenceBlock(runLoop, MissingProducerEvidenceBlockArgs(",
            text,
        )
        text = re.sub(
            r"writeQuarantineRejectedOutput\(\s*\n?\s*run,",
            "writeQuarantineRejectedOutput(runLoop, run,",
            text,
        )
        text = re.sub(
            r"appendQuarantineEntryForRejection\(\s*\n?\s*QuarantineEntryWriteArgs\(",
            "appendQuarantineEntryForRejection(runLoop, QuarantineEntryWriteArgs(",
            text,
        )
        text = text.replace(
            "targeting = rejectedOutputTargeting(",
            "targeting = runLoop.collaborators.attemptSettlement.rejectedOutputTargeting(",
        )

    if path.name == "FeatureTaskRuntimeRunLoopRecordRejectionContinued1.kt":
        text = text.replace(
            "unattributableProducerEvidence(state, output)",
            "unattributableProducerEvidence(runLoop, state, output)",
        )
        text = text.replace(
            "writeUnattributableRejectedEvidence(run, rejection, detail, it)",
            "writeUnattributableRejectedEvidence(runLoop, run, rejection, detail, it)",
        )
        text = text.replace(
            "targeting = rejectedOutputTargeting(",
            "targeting = runLoop.collaborators.attemptSettlement.rejectedOutputTargeting(",
        )

    for name in (
        "FeatureTaskRuntimeRunLoopAttemptSettlementContinued1.kt",
        "FeatureTaskRuntimeRunLoopAttemptSettlementContinued3.kt",
    ):
        if path.name == name:
            text = text.replace(
                "targeting = rejectedOutputTargeting(",
                "targeting = runLoop.collaborators.attemptSettlement.rejectedOutputTargeting(",
            )

    if text != original:
        path.write_text(text)
        return True
    return False


def main() -> None:
    changed = 0
    for path in sorted(ROOT.glob("FeatureTaskRuntimeRunLoop*.kt")):
        if fix_file(path):
            changed += 1
            print(f"fixed {path.name}")
    print(f"updated {changed} files")


if __name__ == "__main__":
    main()
