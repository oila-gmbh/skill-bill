#!/usr/bin/env python3
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / (
    "runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask"
)

PREFIX_TO_CLASS = {
    "FeatureTaskRuntimeRunLoopDrive": "FeatureTaskRuntimeRunLoopDrive",
    "FeatureTaskRuntimeRunLoopPhaseRunner": "FeatureTaskRuntimeRunLoopPhaseRunner",
    "FeatureTaskRuntimeRunLoopPhaseAttempts": "FeatureTaskRuntimeRunLoopPhaseAttempts",
    "FeatureTaskRuntimeRunLoopLaunch": "FeatureTaskRuntimeRunLoopLaunch",
    "FeatureTaskRuntimeRunLoopOutputVerification": "FeatureTaskRuntimeRunLoopOutputVerification",
    "FeatureTaskRuntimeRunLoopOutputPersistence": "FeatureTaskRuntimeRunLoopOutputPersistence",
    "FeatureTaskRuntimeRunLoopValidationGate": "FeatureTaskRuntimeRunLoopValidationGate",
    "FeatureTaskRuntimeRunLoopReview": "FeatureTaskRuntimeRunLoopReview",
    "FeatureTaskRuntimeRunLoopCheckpoint": "FeatureTaskRuntimeRunLoopCheckpoint",
    "FeatureTaskRuntimeRunLoopPlanningBranch": "FeatureTaskRuntimeRunLoopPlanningBranch",
    "FeatureTaskRuntimeRunLoopBackwardEdge": "FeatureTaskRuntimeRunLoopBackwardEdge",
    "FeatureTaskRuntimeRunLoopAttemptSettlement": "FeatureTaskRuntimeRunLoopAttemptSettlement",
    "FeatureTaskRuntimeRunLoopRecordRejection": "FeatureTaskRuntimeRunLoopRecordRejection",
    "FeatureTaskRuntimeRunLoopRepairReceipt": "FeatureTaskRuntimeRunLoopRepairReceipt",
}


def class_for_file(path: Path) -> str | None:
    for prefix, cls in PREFIX_TO_CLASS.items():
        if path.name.startswith(prefix):
            return cls
    return None


def fix_file(path: Path) -> None:
    cls = class_for_file(path)
    if cls is None:
        return
    text = path.read_text()
    original = text
    text = re.sub(
        rf"(private |internal )?fun FeatureTaskRuntimeRunLoop\.(\w+)\(",
        rf"\1fun {cls}.\2(runLoop: FeatureTaskRuntimeRunLoop, ",
        text,
    )
    text = re.sub(
        rf"(\w+)\.session\.(operatorBlockRetry) = runLoop\.session\.\2",
        r"\1 = runLoop.session.\2",
        text,
    )
    for field in [
        "operatorBlockRetry",
        "operatorBlockRetryCompleted",
        "resolvedBranch",
        "checkpointOwnershipDecided",
        "blocked",
        "paused",
        "auditGapRetryResumePending",
        "decomposed",
        "pendingReentry",
        "activeReentry",
        "recordRejectionSettlementPending",
    ]:
        text = re.sub(
            rf"(?<![.\w]){field} = runLoop\.session\.{field}\b",
            f"{field} = runLoop.session.{field}",
            text,
        )
        text = re.sub(
            rf"runLoop\.session\.{field} = runLoop\.session\.{field}",
            f"{field} = runLoop.session.{field}",
            text,
        )
    if text != original:
        path.write_text(text)
        print(f"fixed {path.name}")


def main() -> None:
    for path in sorted(ROOT.glob("FeatureTaskRuntimeRunLoop*.kt")):
        fix_file(path)


if __name__ == "__main__":
    main()
