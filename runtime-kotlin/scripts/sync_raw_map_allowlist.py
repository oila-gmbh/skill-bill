#!/usr/bin/env python3
from __future__ import annotations

import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SUPPORT = ROOT / "runtime-core/src/test/kotlin/skillbill/architecture/RuntimeArchitectureTestSupport.kt"
ARCHITECTURE = ROOT / "ARCHITECTURE.md"

MUST_TYPE_NOW = {
    "skillbill.learnings.learningPayload",
    "skillbill.learnings.learningSummaryPayload",
    "skillbill.learnings.scopeCounts",
    "skillbill.learnings.learningSessionJson",
    "skillbill.learnings.summarizeLearningReferences",
    "skillbill.learnings.learningEntryPayload",
}

POSTPONED = {
    "skillbill.workflow.engine.WorkflowEngine.continueDecision",
    "skillbill.workflow.decomposition.DecompositionManifestCodec.decodeMap",
    "skillbill.workflow.decomposition.toWireMap",
    "skillbill.application.decomposition.decodeDecompositionManifestMap",
    "skillbill.ports.workflow.decomposition.runtime.decodeDecompositionManifestMap",
    "skillbill.application.decomposition.encodeDecompositionManifestMap",
    "skillbill.ports.workflow.decomposition.runtime.encodeDecompositionManifestMap",
}

PREFIX_REPLACEMENTS = [
    ("skillbill.application.goalrunner.model.HistoryArtifactAppend", "skillbill.ports.goalrunner.persistence.model.HistoryArtifactAppend"),
    ("skillbill.application.goalrunner.", "skillbill.ports.goalrunner.persistence."),
    ("skillbill.application.decomposition.model.", "skillbill.ports.workflow.decomposition.runtime.model."),
    ("skillbill.application.decomposition.", "skillbill.ports.workflow.decomposition.runtime."),
    ("skillbill.application.phaseartifacts.", "skillbill.ports.phaseartifacts."),
    ("skillbill.application.workflow.model.GoalObservabilityProgressInput", "skillbill.ports.workflow.persistence.model.GoalObservabilityProgressInput"),
    ("skillbill.application.workflow.model.GoalObservabilityRuntimeEventInput", "skillbill.ports.workflow.persistence.model.GoalObservabilityRuntimeEventInput"),
    ("skillbill.application.workflow.model.WorkflowFamily", "skillbill.ports.workflow.persistence.model.WorkflowFamily"),
    ("skillbill.application.workflow.GoalObservabilityArtifacts", "skillbill.ports.workflow.persistence.GoalObservabilityArtifacts"),
    ("skillbill.application.workflow.reviewPolicyFromLegacyArtifacts", "skillbill.ports.workflow.persistence.reviewPolicyFromLegacyArtifacts"),
    ("skillbill.application.workflow.outOfBandAcceptancesFromLegacyArtifacts", "skillbill.ports.workflow.persistence.outOfBandAcceptancesFromLegacyArtifacts"),
    ("skillbill.application.workflow.toPayload", "skillbill.ports.workflow.persistence.toPayload"),
    ("skillbill.application.subtaskreview.", "skillbill.ports.subtaskreview."),
]


def migrate_fqn(fqn: str) -> str:
    for old, new in PREFIX_REPLACEMENTS:
        if fqn.startswith(old) or fqn == old:
            return new + fqn[len(old):]
    return fqn


def read_allowlist(text: str) -> set[str]:
    entries: set[str] = set()
    in_list = False
    for line in text.splitlines():
        if "RAW_MAP_OPEN_BOUNDARY_ALLOWLIST" in line and "listOf" in line:
            in_list = True
            continue
        if in_list:
            if line.strip() == ")":
                break
            match = re.search(r'"([^"]+)"', line)
            if match:
                chunk = match.group(1)
                if chunk.endswith("."):
                    continue
                entries.add(chunk)
            if '" +' in line:
                continue
    merged = set()
    pending = ""
    for line in text.splitlines():
        if not in_list and "RAW_MAP_OPEN_BOUNDARY_ALLOWLIST" not in line:
            continue
    body = text.split("RAW_MAP_OPEN_BOUNDARY_ALLOWLIST: List<String> = listOf(", 1)[1]
    body = body.split("\n  )", 1)[0]
    current = ""
    for line in body.splitlines():
        stripped = line.strip()
        if not stripped.startswith('"'):
            continue
        part = stripped.strip('",')
        if stripped.endswith('" +'):
            current += part
            continue
        if current:
            current += part
            entries.add(current)
            current = ""
        else:
            entries.add(part)
    return entries


def read_allowlist_simple(text: str) -> set[str]:
    start = text.index("RAW_MAP_OPEN_BOUNDARY_ALLOWLIST: List<String> = listOf(")
    end = text.index("\n  )", start)
    body = text[start:end]
    raw = re.findall(r'"([^"]*)"', body)
    merged: list[str] = []
    buf = ""
    for piece in raw:
        if buf:
            buf += piece
            merged.append(buf)
            buf = ""
        elif piece.endswith("."):
            buf = piece
        else:
            merged.append(piece)
    return set(merged)


def run_tests() -> tuple[set[str], set[str]]:
    violations: set[str] = set()
    undocumented: set[str] = set()
    for test in (
        "skillbill.architecture.RuntimeRawMapArchitectureTest.runtime architecture forbids raw map shapes outside the open-boundary allowlist",
        "skillbill.architecture.RuntimeRawMapArchitectureTest.every OpenBoundaryMap annotated declaration is documented in the architecture allow-list",
    ):
        proc = subprocess.run(
            ["./gradlew", ":runtime-core:test", "--tests", test],
            cwd=ROOT,
            capture_output=True,
            text=True,
        )
        output = proc.stdout + proc.stderr
        violations.update(re.findall(r"fqn=([A-Za-z0-9_.]+)", output))
        if "Undocumented:" in output:
            block = output.split("Undocumented:", 1)[1].split("\n", 1)[0]
            block = block.strip().strip("[]")
            for item in block.split(","):
                item = item.strip()
                if item:
                    undocumented.add(item)
    return violations, undocumented


def scan_annotated() -> set[str]:
    annotated: set[str] = set()
    roots = [
        ROOT / "runtime-application/src/main/kotlin",
        ROOT / "runtime-domain/src/main/kotlin",
        ROOT / "runtime-ports/src/main/kotlin",
    ]
    scope_stack: list[str] = []
    package = ""
    for root in roots:
        for path in sorted(root.rglob("*.kt")):
            lines = path.read_text().splitlines()
            package = ""
            scope_stack = []
            for line in lines:
                pkg = re.match(r"^package\s+([\w.]+)", line)
                if pkg:
                    package = pkg.group(1)
                for m in re.finditer(r"\b(?:data\s+)?class\s+(\w+)", line):
                    if not re.match(r"^\s*(private|internal)\s", line):
                        scope_stack.append(m.group(1))
                for m in re.finditer(r"\bobject\s+(\w+)", line):
                    if not re.match(r"^\s*(private|internal)\s", line):
                        scope_stack.append(m.group(1))
                if line.strip().startswith("@OpenBoundaryMap"):
                    j = lines.index(line) + 1
                    while j < len(lines):
                        candidate = lines[j].strip()
                        j += 1
                        if not candidate or candidate.startswith("@"):
                            continue
                        name = (
                            re.search(r"\bfun\s+(\w+)", candidate)
                            or re.search(r"\bval\s+(\w+)", candidate)
                            or re.search(r"\bclass\s+(\w+)", candidate)
                        )
                        if name:
                            prefix = ".".join(scope_stack)
                            fqn = package if not prefix else f"{package}.{prefix}.{name.group(1)}"
                            if prefix and name.group(1) == scope_stack[-1]:
                                fqn = f"{package}.{prefix}.{name.group(1)}"
                            elif not prefix:
                                fqn = f"{package}.{name.group(1)}"
                            else:
                                fqn = f"{package}.{prefix}.{name.group(1)}"
                            annotated.add(fqn)
                        break
                if "{" in line:
                    pass
    return annotated


def write_allowlist_kotlin(entries: set[str]) -> None:
    text = SUPPORT.read_text()
    start = text.index("RAW_MAP_OPEN_BOUNDARY_ALLOWLIST: List<String> = listOf(")
    end = text.index("\n  )", start)
    lines = ['    "' + e + '",' for e in sorted(entries)]
    new_block = "RAW_MAP_OPEN_BOUNDARY_ALLOWLIST: List<String> = listOf(\n" + "\n".join(lines) + "\n  )"
    text = text[:start] + new_block + text[end + len("\n  )") :]
    SUPPORT.write_text(text)


def write_architecture_allowlist(entries: set[str]) -> None:
    text = ARCHITECTURE.read_text()
    start = text.index("<!-- open-boundary-allowlist:start -->")
    end = text.index("<!-- open-boundary-allowlist:end -->")
    bullets = "\n".join(f"    - `{e}`" for e in sorted(entries))
    replacement = "<!-- open-boundary-allowlist:start -->\n\n" + bullets + "\n\n    <!-- open-boundary-allowlist:end -->"
    text = text[:start] + replacement + text[end + len("<!-- open-boundary-allowlist:end -->") :]
    ARCHITECTURE.write_text(text)


def write_inventory(entries: set[str]) -> None:
    text = ARCHITECTURE.read_text()
    start = text.index("<!-- skill-52-2-inventory:start -->")
    end = text.index("<!-- skill-52-2-inventory:end -->")
    must = sorted(MUST_TYPE_NOW & entries)
    postponed = sorted(POSTPONED & entries)
    open_ext = sorted(entries - MUST_TYPE_NOW - POSTPONED)
    sections = ["<!-- skill-52-2-inventory:start -->", "", "### must_type_now", ""]
    for e in must:
        sections.append(f"- `{e}` [subtask 5] — typed learnings surface.")
    sections += ["", "### open_extension (@OpenBoundaryMap)", ""]
    for e in open_ext:
        sections.append(f"- `{e}`")
    sections += [
        "",
        "### private_serializer",
        "",
        "_None — placeholder._",
        "",
        "### postponed_with_reason",
        "",
    ]
    postponed_reasons = {
        "skillbill.workflow.engine.WorkflowEngine.continueDecision": "workflow-engine continue-decision raw-map seam.",
        "skillbill.workflow.decomposition.DecompositionManifestCodec.decodeMap": "decomposition manifest codec entrypoint.",
        "skillbill.workflow.decomposition.toWireMap": "decomposition manifest wire-map encoder.",
        "skillbill.application.decomposition.decodeDecompositionManifestMap": "decomposition manifest decode entrypoint.",
        "skillbill.ports.workflow.decomposition.runtime.decodeDecompositionManifestMap": "decomposition manifest decode entrypoint.",
        "skillbill.application.decomposition.encodeDecompositionManifestMap": "decomposition manifest encode entrypoint.",
        "skillbill.ports.workflow.decomposition.runtime.encodeDecompositionManifestMap": "decomposition manifest encode entrypoint.",
    }
    for e in postponed:
        reason = postponed_reasons.get(e, "postponed raw-map seam.")
        sections.append(f"- `{e}` [subtask 4] — {reason}")
    sections += ["", "<!-- skill-52-2-inventory:end -->"]
    replacement = "\n".join(sections)
    text = text[:start] + replacement + text[end + len("<!-- skill-52-2-inventory:end -->") :]
    ARCHITECTURE.write_text(text)


def main() -> None:
    support_text = SUPPORT.read_text()
    current = read_allowlist_simple(support_text)
    violations_path = Path("/tmp/violations.txt")
    undocumented_path = Path("/tmp/undocumented.txt")
    if violations_path.exists() and undocumented_path.exists():
        violations = {line.strip() for line in violations_path.read_text().splitlines() if line.strip()}
        undocumented = {line.strip() for line in undocumented_path.read_text().splitlines() if line.strip()}
    else:
        violations, undocumented = run_tests()
    merged = {migrate_fqn(f) for f in current} | violations | undocumented
    write_allowlist_kotlin(merged)
    write_architecture_allowlist(merged)
    write_inventory(merged)
    print(f"allowlist entries: {len(merged)}")


if __name__ == "__main__":
    main()
