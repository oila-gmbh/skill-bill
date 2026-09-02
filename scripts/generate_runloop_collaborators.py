#!/usr/bin/env python3
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / (
    "runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask"
)

RUN_LOOP_PARAM = "runLoop"

COLLABORATOR_FILES = {
    "Drive": [
        "FeatureTaskRuntimeRunLoopDrive.kt",
        "FeatureTaskRuntimeRunLoopDriveAdvance.kt",
        "FeatureTaskRuntimeRunLoopDriveExtras.kt",
        "FeatureTaskRuntimeRunLoopDriveExtras2.kt",
    ],
    "PhaseRunner": [
        "FeatureTaskRuntimeRunLoopPhaseRunner.kt",
        "FeatureTaskRuntimeRunLoopPhaseRunnerPreLaunch.kt",
        "FeatureTaskRuntimeRunLoopPhaseRunnerGoalReview.kt",
        "FeatureTaskRuntimeRunLoopPhaseRunnerGoalReviewExtras.kt",
    ],
    "PhaseAttempts": [
        "FeatureTaskRuntimeRunLoopPhaseAttempts.kt",
        "FeatureTaskRuntimeRunLoopPhaseAttemptsFixLoop.kt",
        "FeatureTaskRuntimeRunLoopPhaseAttemptsFixLoopExtras.kt",
        "FeatureTaskRuntimeRunLoopPhaseAttemptsFixLoopExtras2.kt",
    ],
    "Launch": [
        "FeatureTaskRuntimeRunLoopLaunchPrepare.kt",
        "FeatureTaskRuntimeRunLoopLaunchPrepareExtras.kt",
        "FeatureTaskRuntimeRunLoopLaunchCapture.kt",
        "FeatureTaskRuntimeRunLoopLaunchCaptureExtras.kt",
    ],
    "OutputVerification": [
        "FeatureTaskRuntimeRunLoopOutputVerification.kt",
        "FeatureTaskRuntimeRunLoopOutputVerificationGate.kt",
        "FeatureTaskRuntimeRunLoopOutputVerificationPersist.kt",
        "FeatureTaskRuntimeRunLoopOutputVerificationExtras.kt",
        "FeatureTaskRuntimeRunLoopOutputVerificationPersistExtras.kt",
    ],
    "OutputPersistence": [
        "FeatureTaskRuntimeRunLoopOutputPersistence.kt",
        "FeatureTaskRuntimeRunLoopOutputPersistenceExtras.kt",
    ],
    "ValidationGate": [
        "FeatureTaskRuntimeRunLoopValidationBuildGate.kt",
        "FeatureTaskRuntimeRunLoopValidationBuildGateExtras.kt",
        "FeatureTaskRuntimeRunLoopValidationBuildGateRepair.kt",
        "FeatureTaskRuntimeRunLoopValidationBuildGateRepairExtras.kt",
        "FeatureTaskRuntimeRunLoopValidationBuildGateRepairExtras2.kt",
        "FeatureTaskRuntimeRunLoopReviewDriverValidationGate.kt",
        "FeatureTaskRuntimeRunLoopReviewDriverValidationGateExtras.kt",
    ],
    "Review": [
        "FeatureTaskRuntimeRunLoopReviewDriver.kt",
        "FeatureTaskRuntimeRunLoopReviewDriverSettlement.kt",
    ],
    "Checkpoint": [
        "FeatureTaskRuntimeRunLoopCheckpointScope.kt",
        "FeatureTaskRuntimeRunLoopCheckpointScopeExtras.kt",
        "FeatureTaskRuntimeRunLoopCheckpointEstablish.kt",
        "FeatureTaskRuntimeRunLoopCheckpointEstablishExtras.kt",
        "FeatureTaskRuntimeRunLoopCheckpointCommit.kt",
        "FeatureTaskRuntimeRunLoopCheckpointCommitExtras.kt",
    ],
    "PlanningBranch": [
        "FeatureTaskRuntimeRunLoopPlanningBranch.kt",
        "FeatureTaskRuntimeRunLoopPlanningBranchExtras.kt",
        "FeatureTaskRuntimeRunLoopPlanningBranchExtras2.kt",
    ],
    "BackwardEdge": [
        "FeatureTaskRuntimeRunLoopBackwardEdge.kt",
        "FeatureTaskRuntimeRunLoopBackwardEdgeExtras.kt",
    ],
    "AttemptSettlement": [
        "FeatureTaskRuntimeRunLoopAttemptSettlement.kt",
        "FeatureTaskRuntimeRunLoopAttemptSettlementExtras.kt",
        "FeatureTaskRuntimeRunLoopAttemptValidated.kt",
    ],
    "RecordRejection": [
        "FeatureTaskRuntimeRunLoopRecordRejection.kt",
        "FeatureTaskRuntimeRunLoopRecordRejectionExtras.kt",
        "FeatureTaskRuntimeRunLoopRecordRejectionAttempt.kt",
        "FeatureTaskRuntimeRunLoopRejectionScrub.kt",
    ],
    "RepairReceipt": [
        "FeatureTaskRuntimeRunLoopRepairReceipt.kt",
        "FeatureTaskRuntimeRunLoopRepairReceiptExtras.kt",
    ],
    "SubtaskCommit": ["FeatureTaskRuntimeRunLoopSubtaskCommit.kt"],
    "Transitions": ["FeatureTaskRuntimeRunLoopTransitions.kt"],
}

SESSION_FIELDS = [
    "phaseContentIdentities",
    "resolvedBranch",
    "checkpointOwnershipDecided",
    "blocked",
    "paused",
    "auditGapRetryResumePending",
    "decomposed",
    "operatorBlockRetry",
    "operatorBlockRetryCompleted",
    "pendingReentry",
    "activeReentry",
    "recordRejectionSettlementPending",
]

LOOP_PROPS_ALWAYS = [
    "request",
    "recorder",
    "goalContinuationRecorder",
    "outputValidator",
    "phaseGates",
    "subtaskLauncher",
    "phaseSettlementService",
    "activityStampWriter",
    "clock",
    "branchSetupRunner",
    "planningStopper",
    "gitOperations",
    "planningProjectionValidator",
    "buildReceiptValidator",
    "validationGateCoordinator",
    "buildGateCoordinator",
    "diagnostics",
    "specSource",
    "transitions",
    "phaseTokenAccumulator",
    "dependencies",
    "collaborators",
    "goalContinuationManifestCommitSha",
    "session",
]


def collab_prop(name: str) -> str:
    return name[0].lower() + name[1:]


def build_func_map() -> dict[str, str]:
    func_map: dict[str, str] = {}
    for collab, files in COLLABORATOR_FILES.items():
        for file_name in files:
            path = ROOT / file_name
            if not path.exists():
                continue
            text = path.read_text()
            for match in re.finditer(
                r"internal fun FeatureTaskRuntimeRunLoop\.(\w+)", text
            ):
                func_map[match.group(1)] = collab
            for match in re.finditer(
                rf"internal fun FeatureTaskRuntimeRunLoop{collab}\.(\w+)",
                text,
            ):
                func_map[match.group(1)] = collab
    return func_map


FUNC_TO_COLLAB = build_func_map()


def strip_header(content: str) -> str:
    content = re.sub(r"^package skillbill\.application\.featuretask\s*\n+", "", content)
    content = re.sub(r"@Inject\s*\nclass \w+Holder\s*\n", "", content)
    lines = []
    for line in content.splitlines():
        if line.startswith("import "):
            continue
        lines.append(line)
    return "\n".join(lines).strip()


def transform_signature(content: str) -> str:
    content = re.sub(
        r"internal fun FeatureTaskRuntimeRunLoop\.(\w+)\(",
        rf"internal fun \1({RUN_LOOP_PARAM}: FeatureTaskRuntimeRunLoop, ",
        content,
    )
    return content


def param_names_from_signature(signature: str) -> set[str]:
    match = re.search(r"fun \w+\((.*)\)", signature, re.DOTALL)
    if not match:
        return set()
    return {m.group(1) for m in re.finditer(r"(\w+)\s*:", match.group(1))}


def transform_default_params(signature: str, own_collab: str) -> str:
    def replacer(match: re.Match[str]) -> str:
        fn = match.group(1)
        if fn not in FUNC_TO_COLLAB:
            return match.group(0)
        collab = FUNC_TO_COLLAB[fn]
        if collab == own_collab:
            return f"= {fn}({RUN_LOOP_PARAM})"
        return f"= {RUN_LOOP_PARAM}.collaborators.{collab_prop(collab)}.{fn}({RUN_LOOP_PARAM})"

    return re.sub(r"=\s*(\w+)\(\)", replacer, signature)


def transform_line(line: str, param_names: set[str], own_collab: str) -> str:
    if line.strip().startswith("import "):
        return line
    if "::" in line:
        return line
    is_comment = line.strip().startswith("//") or line.strip().startswith("*")
    result = line
    if not is_comment:
        for field in SESSION_FIELDS:
            result = re.sub(
                rf"(?<![.\w]){field}\s*=",
                f"{RUN_LOOP_PARAM}.session.{field} =",
                result,
            )
        for field in SESSION_FIELDS:
            if field not in param_names:
                result = re.sub(
                    rf"(?<![.\w]){field}\b(?!\s*[:=])",
                    f"{RUN_LOOP_PARAM}.session.{field}",
                    result,
                )
    for prop in LOOP_PROPS_ALWAYS:
        result = re.sub(rf"(?<![.\w]){prop}\.", f"{RUN_LOOP_PARAM}.{prop}.", result)
        result = re.sub(
            rf"(?<![.\w]){prop}\b(?!\s*[:=])",
            f"{RUN_LOOP_PARAM}.{prop}",
            result,
        )
    if "state" not in param_names:
        result = re.sub(r"(?<![.\w])state\.", f"{RUN_LOOP_PARAM}.state.", result)
        result = re.sub(r"(?<![.\w])state\b(?!\s*[:=])", f"{RUN_LOOP_PARAM}.state", result)
    if "observability" not in param_names:
        result = re.sub(r"(?<![.\w])observability\.", f"{RUN_LOOP_PARAM}.observability.", result)
        result = re.sub(
            r"(?<![.\w])observability\b(?!\s*[:=])",
            f"{RUN_LOOP_PARAM}.observability",
            result,
        )
    for fn in sorted(FUNC_TO_COLLAB, key=len, reverse=True):
        collab = FUNC_TO_COLLAB[fn]
        if collab == own_collab:
            replacement = f"{fn}({RUN_LOOP_PARAM}, "
        else:
            replacement = (
                f"{RUN_LOOP_PARAM}.collaborators.{collab_prop(collab)}.{fn}({RUN_LOOP_PARAM}, "
            )
        result = re.sub(rf"(?<![.\w]){fn}\(", replacement, result)
    result = re.sub(rf"\({RUN_LOOP_PARAM}, \)", f"({RUN_LOOP_PARAM})", result)
    result = re.sub(r", \)", ")", result)
    return result


def consume_signature(lines: list[str], start: int) -> tuple[list[str], int, set[str]]:
    signature: list[str] = []
    depth = 0
    started = False
    index = start
    while index < len(lines):
        line = lines[index]
        signature.append(line)
        for char in line:
            if char == "(":
                depth += 1
                started = True
            elif char == ")":
                depth -= 1
        if started and depth == 0 and (
            "{" in line
            or re.search(r"\)\s*=", line)
            or re.search(r":[^=]*=", line)
        ):
            break
        index += 1
    signature_text = "\n".join(signature)
    return signature, index + 1, param_names_from_signature(signature_text)


def transform_expression_suffix(signature: str, param_names: set[str], own_collab: str) -> str:
    if "=" not in signature:
        return signature
    equals_index = signature.index("=")
    prefix = signature[: equals_index + 1]
    suffix = signature[equals_index + 1 :]
    return prefix + transform_line(suffix, param_names, own_collab)


def transform_body(content: str, own_collab: str) -> str:
    lines = content.splitlines()
    output: list[str] = []
    index = 0
    current_params: set[str] = set()
    while index < len(lines):
        line = lines[index]
        if re.search(r"^\s*internal (fun |enum class |data class |class )", line):
            signature, index, current_params = consume_signature(lines, index)
            signature_text = "\n".join(signature)
            transformed_signature = transform_default_params(signature_text, own_collab)
            transformed_signature = transform_expression_suffix(
                transformed_signature, current_params, own_collab
            )
            output.extend(transformed_signature.splitlines())
            continue
        output.append(transform_line(line, current_params, own_collab))
        index += 1
    return "\n".join(output)


def collect_imports(files: list[str]) -> list[str]:
    imports: set[str] = set()
    for file_name in files:
        path = ROOT / file_name
        if not path.exists():
            continue
        for line in path.read_text().splitlines():
            if line.startswith("import "):
                imports.add(line)
    return sorted(imports)


def split_chunks(content: str, max_lines: int = 480) -> list[str]:
    positions = [m.start() for m in re.finditer(r"^internal (fun |enum class )", content, re.M)]
    if not positions:
        return [content]
    chunks: list[str] = []
    start = 0
    accumulated = 0
    for position in positions[1:]:
        line_count = content[start:position].count("\n")
        if accumulated + line_count > max_lines and accumulated > 0:
            chunks.append(content[start:position].rstrip())
            start = position
            accumulated = line_count
        else:
            accumulated += line_count
    chunks.append(content[start:].rstrip())
    return chunks


def cleanup_content(content: str) -> str:
    content = re.sub(
        rf"\({RUN_LOOP_PARAM}: FeatureTaskRuntimeRunLoop, \)",
        f"({RUN_LOOP_PARAM}: FeatureTaskRuntimeRunLoop)",
        content,
    )
    content = re.sub(r", \)", ")", content)
    return content


def write_collaborators_registry() -> None:
    props = [collab_prop(name) for name in COLLABORATOR_FILES]
    class_names = [f"FeatureTaskRuntimeRunLoop{name}" for name in COLLABORATOR_FILES]
    fields = "\n".join(
        f"  val {prop}: {cls}," for prop, cls in zip(props, class_names, strict=True)
    )
    ctor_args = "\n".join(
        f"    {prop} = {cls}()," for prop, cls in zip(props, class_names, strict=True)
    )
    content = f"""package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject

@Inject
class FeatureTaskRuntimeRunLoopCollaborators(
{fields}
)
"""
    (ROOT / "FeatureTaskRuntimeRunLoopCollaborators.kt").write_text(content)


def main() -> None:
    global FUNC_TO_COLLAB
    FUNC_TO_COLLAB = build_func_map()
    work_dir = ROOT
    delete_sources: list[str] = []
    written_outputs: set[str] = set()
    for collab, files in COLLABORATOR_FILES.items():
        class_name = f"FeatureTaskRuntimeRunLoop{collab}"
        imports = collect_imports(files)
        parts: list[str] = []
        for file_name in files:
            path = work_dir / file_name
            if not path.exists():
                print(f"missing {file_name}")
                continue
            transformed = transform_body(
                transform_signature(strip_header(path.read_text())), collab
            )
            parts.append(transformed)
            delete_sources.append(file_name)
        merged = cleanup_content("\n\n".join(parts))
        chunks = split_chunks(merged)
        for chunk_index, chunk in enumerate(chunks):
            if chunk_index == 0:
                out_path = f"{class_name}.kt"
                body = "\n".join(
                    f"  {line}" if line.strip() else line for line in chunk.splitlines()
                )
                content = (
                    "package skillbill.application.featuretask\n\n"
                    "import me.tatarka.inject.annotations.Inject\n"
                    + "\n".join(imp for imp in imports if "tatarka" not in imp)
                    + f"\n\n@Inject\ninternal class {class_name} {{\n{body}\n}}\n"
                )
            else:
                out_path = f"{class_name}Continued{chunk_index}.kt"
                extension_chunk = re.sub(
                    r"^internal fun ",
                    f"internal fun {class_name}.",
                    chunk,
                    flags=re.M,
                )
                content = (
                    "package skillbill.application.featuretask\n\n"
                    + "\n".join(imports)
                    + "\n\n"
                    + extension_chunk
                    + "\n"
                )
            Path(work_dir / out_path).write_text(content)
            written_outputs.add(out_path)
            print(f"wrote {out_path}: {content.count(chr(10)) + 1} lines")
    for file_name in delete_sources:
        if file_name in written_outputs:
            continue
        Path(work_dir / file_name).unlink(missing_ok=True)
    write_collaborators_registry()
    print(f"deleted {len([f for f in delete_sources if f not in written_outputs])} source files")


if __name__ == "__main__":
    main()
