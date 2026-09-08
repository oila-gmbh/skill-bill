#!/usr/bin/env python3
import re
from pathlib import Path

BASE = Path("runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask")
import subprocess

ORIGINAL = Path("/tmp/FeatureTaskRuntimeRunLoop.original.kt")
src_path = BASE / "FeatureTaskRuntimeRunLoop.kt"
if ORIGINAL.exists():
    src_text = ORIGINAL.read_text()
elif src_path.exists() and src_path.read_text().count("\n") >= 1000:
    src_text = src_path.read_text()
else:
    src_text = subprocess.check_output(
        [
            "git",
            "show",
            "3bd332ad1:runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimeRunLoop.kt",
        ],
        text=True,
    )
lines = src_text.splitlines(keepends=True)

CLASS_START = next(i for i, l in enumerate(lines) if l.startswith("internal class FeatureTaskRuntimeRunLoop("))
OPEN_BRACE = CLASS_START
while "{" not in lines[OPEN_BRACE]:
    OPEN_BRACE += 1
depth = 0
CLASS_END = OPEN_BRACE
for i in range(OPEN_BRACE, len(lines)):
    for ch in lines[i]:
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
    if depth == 0:
        CLASS_END = i
        break
FIELDS_START = OPEN_BRACE + 1
CLASS_HEADER_LINES = lines[CLASS_START : OPEN_BRACE + 1]
IMPORT_END = next(i for i, l in enumerate(lines) if l.startswith("internal data class FeatureTaskRuntimeRunLoopDependencies"))
PACKAGE_LINE = next(i for i, l in enumerate(lines) if l.startswith("package "))
MODULE_PRELUDE = "".join(lines[PACKAGE_LINE:IMPORT_END])
TAIL = "".join(lines[CLASS_END + 1 :])


def is_class_level_decl(line: str) -> bool:
    if not line.startswith("  ") or line.startswith("    "):
        return False
    stripped = line.strip()
    if stripped.startswith("@Suppress"):
        return True
    return bool(
        re.match(r"^(private |internal )?(suspend )?fun ", stripped)
        or re.match(r"^fun ", stripped)
        or re.match(r"^private (data class|sealed|enum class|sealed class|class)", stripped)
        or re.match(r"^(private |internal )(val|var) ", stripped)
    )


def decl_end_type(start: int) -> int:
    line = lines[start]
    if re.match(r"^  private data object \w+", line):
        return start + 1
    if re.match(r"^  private data class \w+\(", line):
        if re.search(r"\)\s*(:|$)", line.rstrip()):
            return start + 1
        i = start + 1
        while i <= CLASS_END:
            if lines[i].strip().startswith(")"):
                return i + 1
            i += 1
    if re.match(r"^  private class \w+\(", line):
        if line.rstrip().endswith(")"):
            return start + 1
        i = start + 1
        while i <= CLASS_END:
            stripped = lines[i].strip()
            if stripped == ")" or (stripped.startswith(")") and stripped.endswith(")")):
                return i + 1
            i += 1
    if re.match(r"^  private class \w+", line) and "{" not in line:
        return start + 1
    if re.match(r"^  private sealed (class|interface) \w+", line) and "{" not in line and "(" not in line:
        return start + 1
    depth = 0
    started = False
    for i in range(start, CLASS_END + 1):
        for ch in lines[i]:
            if ch == "{":
                depth += 1
                started = True
            elif ch == "}":
                depth -= 1
                if started and depth == 0:
                    return i + 1
    return CLASS_END


def decl_end_method(start: int) -> int:
    i = start + 1
    while i < CLASS_END:
        if is_class_level_decl(lines[i]):
            return i
        i += 1
    return CLASS_END


decls = []
i = FIELDS_START
while i < CLASS_END:
    while i < CLASS_END and lines[i].strip().startswith("@Suppress"):
        i += 1
    if i >= CLASS_END:
        break
    line = lines[i]
    if not line.startswith("  ") or line.startswith("    "):
        i += 1
        continue
    if re.match(r"^  (private |internal )?(suspend )?fun ", line) or re.match(r"^  fun ", line):
        end = decl_end_method(i)
        name = re.search(r"fun ([\w.]+)", line).group(1)
        decls.append(("method", i, end, name))
        i = end
    elif re.match(r"^  private (data class|data object|sealed|enum class|sealed class|class)", line):
        end = decl_end_type(i)
        decls.append(("type", i, end, line.strip()[:40]))
        i = end
    elif re.match(r"^  (private |internal )(val|var) ", line):
        decls.append(("field", i, i + 1, ""))
        i += 1
    elif line.strip().startswith("//") or line.strip() == "":
        decls.append(("comment", i, i + 1, ""))
        i += 1
    else:
        i += 1

methods = [d for d in decls if d[0] == "method"]
types = [d for d in decls if d[0] == "type"]
KEEP_METHODS = {"drive", "advance", "report", "applyOperatorDecision"}

FILE_RANGES = [
    ("FeatureTaskRuntimeRunLoopDrive.kt", 291, 650),
    ("FeatureTaskRuntimeRunLoopDriveAdvance.kt", 650, 784),
    ("FeatureTaskRuntimeRunLoopTransitions.kt", 784, 882),
    ("FeatureTaskRuntimeRunLoopCheckpointEstablish.kt", 882, 1150),
    ("FeatureTaskRuntimeRunLoopCheckpointScope.kt", 1150, 1436),
    ("FeatureTaskRuntimeRunLoopRepairReceipt.kt", 1436, 1615),
    ("FeatureTaskRuntimeRunLoopCheckpointCommit.kt", 1615, 1852),
    ("FeatureTaskRuntimeRunLoopBackwardEdge.kt", 1852, 2116),
    ("FeatureTaskRuntimeRunLoopPlanningBranch.kt", 2116, 2410),
    ("FeatureTaskRuntimeRunLoopPhaseRunner.kt", 2410, 2872),
    ("FeatureTaskRuntimeRunLoopReviewDriver.kt", 2872, 3212),
    ("FeatureTaskRuntimeRunLoopValidationBuildGate.kt", 3212, 3440),
    ("FeatureTaskRuntimeRunLoopValidationBuildGateRepair.kt", 3440, 3672),
    ("FeatureTaskRuntimeRunLoopPhaseAttempts.kt", 3672, 4050),
    ("FeatureTaskRuntimeRunLoopPhaseAttemptsFixLoop.kt", 4050, 4205),
    ("FeatureTaskRuntimeRunLoopRecordRejection.kt", 4205, 4518),
    ("FeatureTaskRuntimeRunLoopAttemptSettlement.kt", 4518, 4777),
    ("FeatureTaskRuntimeRunLoopAttemptValidated.kt", 4777, 4995),
    ("FeatureTaskRuntimeRunLoopSubtaskCommit.kt", 4995, 5131),
    ("FeatureTaskRuntimeRunLoopOutputVerification.kt", 5131, 5450),
    ("FeatureTaskRuntimeRunLoopOutputVerificationGate.kt", 5450, 5600),
    ("FeatureTaskRuntimeRunLoopOutputVerificationPersist.kt", 5600, 5752),
    ("FeatureTaskRuntimeRunLoopOutputPersistence.kt", 5752, 6095),
    ("FeatureTaskRuntimeRunLoopLaunchPrepare.kt", 6095, 6359),
    ("FeatureTaskRuntimeRunLoopLaunchCapture.kt", 6359, 6660),
]


def transform_method(block: list[str]) -> list[str]:
    out = []
    for line in block:
        if '@Suppress("LargeClass"' in line or '@Suppress("TooManyFunctions"' in line:
            continue
        m = re.match(r"^  private fun ([\w.]+)\(", line)
        if m:
            name = m.group(1)
            if "." in name:
                line = re.sub(r"^  private fun ", "internal fun ", line, count=1)
            else:
                line = f"internal fun FeatureTaskRuntimeRunLoop.{name}(" + line.split("(", 1)[1]
        elif re.match(r"^  internal fun ", line):
            line = re.sub(r"^  internal fun ", "internal fun FeatureTaskRuntimeRunLoop.", line)
        elif re.match(r"^  fun ", line):
            line = re.sub(r"^  fun ", "internal fun FeatureTaskRuntimeRunLoop.", line)
        elif re.match(r"^  private suspend fun ", line):
            line = re.sub(r"^  private suspend fun ", "internal suspend fun FeatureTaskRuntimeRunLoop.", line)
        out.append(line)
    return out


def transform_type(block: list[str]) -> list[str]:
    out = []
    interface_depth = 0
    for line in block:
        if line.startswith("  "):
            line = line[2:]
        stripped = line.lstrip()
        if re.match(r"(private |internal )?sealed interface ", stripped):
            line = re.sub(r"^private sealed interface ", "internal sealed interface ", line)
            interface_depth = 0
        elif re.match(r"(private |internal )?interface ", stripped) and "{" in stripped:
            line = re.sub(r"^private interface ", "internal interface ", line)
            interface_depth = 0
        elif stripped.startswith("}"):
            if interface_depth > 0:
                interface_depth -= 1
        elif "{" in stripped and interface_depth >= 0 and re.match(
            r"(private |internal )?(sealed )?(class|interface|enum class) ", stripped
        ):
            interface_depth += stripped.count("{") - stripped.count("}")
        elif interface_depth > 0:
            line = line.replace("private data class", "data class").replace("private enum class", "enum class")
        else:
            line = (
                line.replace("private sealed", "internal sealed")
                .replace("private data", "internal data")
                .replace("private enum", "internal enum")
                .replace("private class", "internal class")
            )
        out.append(line)
    return out


def transform_visibility(line: str) -> str:
    if "private val request" in line:
        return line.replace("private val request", "internal val request")
    if "private val diagnostics" in line:
        return line.replace("private val diagnostics", "internal val diagnostics")
    if line.startswith("  private val "):
        return line.replace("private val ", "internal val ", 1)
    if line.startswith("  private var "):
        return line.replace("private var ", "internal var ", 1)
    return line


def transform_fields(block: list[str]) -> list[str]:
    return [transform_visibility(line) for line in block]


type_lines: list[str] = []
for _, s, e, _ in types:
    type_lines.extend(transform_type(lines[s:e]))
    type_lines.append("\n")

tail_models: list[str] = []
for line in TAIL.splitlines(keepends=True):
    if line.strip().startswith("private fun scrubOffVocabulary"):
        break
    tail_models.append(line)

models_chunks: list[list[str]] = []
current: list[str] = []
current_lines = 0
for _, s, e, _ in types:
    block = transform_type(lines[s:e]) + ["\n"]
    block_lines = len(block)
    if current and current_lines + block_lines > 280:
        models_chunks.append(current)
        current = []
        current_lines = 0
    current.extend(block)
    current_lines += block_lines
if current:
    models_chunks.append(current)

models_header = MODULE_PRELUDE + "\n"
model_names = [
    "FeatureTaskRuntimeRunLoopModels.kt",
    "FeatureTaskRuntimeRunLoopAttemptModels.kt",
    "FeatureTaskRuntimeRunLoopLaunchModels.kt",
]
for idx, chunk in enumerate(models_chunks):
    name = model_names[idx] if idx < len(model_names) else f"FeatureTaskRuntimeRunLoopModels{idx}.kt"
    tail = "".join(tail_models) if idx == len(models_chunks) - 1 else ""
    (BASE / name).write_text(models_header + "".join(chunk) + tail)

for fname, lo, hi in FILE_RANGES:
    body: list[str] = []
    for m in methods:
        if (lo - 1) <= m[1] < (hi - 1) and m[3] not in KEEP_METHODS:
            body.extend(transform_method(lines[m[1] : m[2]]))
    content = MODULE_PRELUDE + "\n" + "".join(body)
    (BASE / fname).write_text(content)

first_method_line = min(m[1] for m in methods)
main_fields = transform_fields(lines[FIELDS_START:first_method_line])
main_methods: list[str] = []
for m in methods:
    if m[3] in KEEP_METHODS:
        main_methods.extend(lines[m[1] : m[2]])
main_body = main_fields + main_methods

header = [transform_visibility(l) for l in CLASS_HEADER_LINES if not l.strip().startswith("@Suppress")]
scrub_tail: list[str] = []
capture = False
for line in TAIL.splitlines(keepends=True):
    if "scrubOffVocabulary" in line or capture:
        capture = True
        scrub_tail.append(line)

main_content = "".join(lines[:CLASS_START]) + "".join(header) + "".join(main_body) + "}\n"
(BASE / "FeatureTaskRuntimeRunLoop.kt").write_text(main_content)

scrub_path = BASE / "FeatureTaskRuntimeRunLoopRejectionScrub.kt"
scrub_path.write_text(
    "package skillbill.application.featuretask\n\n"
    + "".join(l.replace("private fun ", "internal fun ").replace("private const val ", "internal const val ").replace("private val ", "internal val ") for l in scrub_tail)
)

for f in ["FeatureTaskRuntimeRunLoop.kt", "FeatureTaskRuntimeRunLoopRejectionScrub.kt"] + model_names[: len(models_chunks)] + [x[0] for x in FILE_RANGES]:
    lc = (BASE / f).read_text().count("\n")
    print(f"{lc:4d} {f}{' OVER' if lc > 500 else ''}")
print(f"\nTypes: {len(types)} Methods kept in main: {[m[3] for m in methods if m[3] in KEEP_METHODS]}")
