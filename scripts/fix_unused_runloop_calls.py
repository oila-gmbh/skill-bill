#!/usr/bin/env python3
"""Fix remaining extension-function unused params and strip call-site args."""

from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]


def find_matching_paren(text: str, open_idx: int) -> int:
    depth = 0
    i = open_idx
    while i < len(text):
        ch = text[i]
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return i
        elif ch in ('"', "'"):
            quote = ch
            i += 1
            while i < len(text):
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == quote:
                    break
                i += 1
        i += 1
    return -1


def split_params(params: str) -> list[str]:
    parts: list[str] = []
    depth = 0
    cur: list[str] = []
    for ch in params:
        if ch in "(<[":
            depth += 1
            cur.append(ch)
        elif ch in ")]>":
            depth -= 1
            cur.append(ch)
        elif ch == "," and depth == 0:
            parts.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
    if cur:
        parts.append("".join(cur))
    return parts


def strip_param(params: str, name: str) -> str | None:
    kept: list[str] = []
    removed = False
    for part in split_params(params):
        if re.match(rf"\s*{re.escape(name)}\s*:", part) and not removed:
            removed = True
            continue
        kept.append(part)
    if not removed:
        return None
    rebuilt = ",".join(kept)
    rebuilt = re.sub(r",\s*,", ",", rebuilt)
    rebuilt = re.sub(r"^\s*,", "", rebuilt)
    rebuilt = re.sub(r",\s*$", "", rebuilt)
    return rebuilt


def remove_named_arg_from_call(args: str, name: str) -> str | None:
    """Remove positional-first or named `name = ...` argument from call args."""
    parts = split_params(args)
    if not parts:
        return None
    kept: list[str] = []
    removed = False
    for i, part in enumerate(parts):
        stripped = part.strip()
        if not removed and (
            stripped == name
            or stripped.startswith(f"{name}=")
            or stripped.startswith(f"{name} =")
            or (i == 0 and stripped in (name, f"{name}"))
        ):
            # first positional often just `runLoop` or `sweep`
            if stripped == name or re.match(rf"^{re.escape(name)}\s*$", stripped):
                removed = True
                continue
            if re.match(rf"^{re.escape(name)}\s*=", stripped):
                removed = True
                continue
        kept.append(part)
    if not removed:
        # also try first positional always if name is runLoop/sweep and first arg is identifier
        if parts and re.match(r"^\s*(runLoop|sweep)\s*$", parts[0]):
            kept = parts[1:]
            removed = True
    if not removed:
        return None
    rebuilt = ",".join(kept)
    rebuilt = re.sub(r",\s*,", ",", rebuilt)
    rebuilt = re.sub(r"^\s*,", "", rebuilt)
    rebuilt = re.sub(r",\s*$", "", rebuilt)
    return rebuilt


def fix_extension_sigs() -> list[tuple[str, str]]:
    remaining = [
        (
            "runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimeRunLoopReviewContinued1.kt",
            "failedReviewLaneReason",
            "runLoop",
        ),
        (
            "runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimeRunLoopBackwardEdgeContinued1.kt",
            "blocksWhenCapExhausted",
            "runLoop",
        ),
        (
            "runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimeRunLoopOutputPersistenceContinued1.kt",
            "launchedModelDirective",
            "runLoop",
        ),
        (
            "runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimeRunLoopPlanningBranchContinued1.kt",
            "remediationCheckpointBlockedReason",
            "runLoop",
        ),
        (
            "runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimeRunLoopPlanningBranchContinued1.kt",
            "auditReviewCheckpointBlockedReason",
            "runLoop",
        ),
        (
            "runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimeRunLoopRecordRejectionContinued1.kt",
            "unattributableRecordRejectionReason",
            "runLoop",
        ),
    ]
    changed: list[tuple[str, str]] = []
    for rel, fun_name, param in remaining:
        path = REPO / rel
        text = path.read_text()
        # Match extension: fun Receiver.funName(
        pattern = rf"\bfun\s+[A-Za-z_][\w.]*\.{re.escape(fun_name)}\s*\("
        m = re.search(pattern, text)
        if not m:
            print("NO EXT", fun_name)
            continue
        open_abs = m.end() - 1
        close_abs = find_matching_paren(text, open_abs)
        params = text[open_abs + 1 : close_abs]
        new_params = strip_param(params, param)
        if new_params is None:
            print("NO PARAM", fun_name)
            continue
        text = text[: open_abs + 1] + new_params + text[close_abs:]
        path.write_text(text)
        changed.append((fun_name, param))
        print(f"EXT SIG {fun_name} -{param}")
    return changed


def load_changed_methods() -> list[tuple[str, str]]:
    methods: list[tuple[str, str]] = []
    list_path = REPO / "scripts/removed_unused_params.txt"
    if list_path.is_file():
        for line in list_path.read_text().splitlines():
            if not line.strip():
                continue
            _path, fun, param = line.split("|")
            methods.append((fun, param))
    methods.extend(fix_extension_sigs())
    # dedupe
    seen = set()
    out = []
    for item in methods:
        if item in seen:
            continue
        seen.add(item)
        out.append(item)
    return out


def strip_calls_for_methods(methods: list[tuple[str, str]]) -> int:
    """Strip first/named arg from calls to changed methods across runtime-application."""
    roots = [
        REPO / "runtime-kotlin/runtime-application/src/main/kotlin",
        REPO / "runtime-kotlin/runtime-application/src/test/kotlin",
    ]
    method_params = {fun: param for fun, param in methods}
    total = 0
    for root in roots:
        for path in root.rglob("*.kt"):
            text = path.read_text()
            original = text
            for fun, param in method_params.items():
                # Find .fun( or fun( call sites; skip definitions
                for m in list(re.finditer(rf"(?<!fun )(?<!\.)\b{re.escape(fun)}\s*\(|\.{re.escape(fun)}\s*\(", text)):
                    # skip if this is a definition (preceded by fun somewhere on same "statement")
                    start = m.start()
                    line_start = text.rfind("\n", 0, start) + 1
                    prefix = text[line_start:start]
                    if re.search(r"\bfun\b", prefix):
                        continue
                    open_idx = m.end() - 1
                    close_idx = find_matching_paren(text, open_idx)
                    if close_idx < 0:
                        continue
                    args = text[open_idx + 1 : close_idx]
                    # Only strip if first positional is runLoop/sweep or named
                    parts = split_params(args)
                    if not parts:
                        continue
                    first = parts[0].strip()
                    should = (
                        first == param
                        or first.startswith(f"{param}=")
                        or first.startswith(f"{param} =")
                    )
                    if not should:
                        continue
                    new_args = remove_named_arg_from_call(args, param)
                    if new_args is None:
                        continue
                    text = text[: open_idx + 1] + new_args + text[close_idx:]
                    total += 1
                    # restart search on updated text for this fun by breaking to outer rebuild
            if text != original:
                path.write_text(text)
                print(f"CALLS updated in {path.relative_to(REPO)}")
    return total


def main() -> None:
    methods = load_changed_methods()
    print(f"Methods to fix calls for: {len(methods)}")
    # rewrite list including extensions
    (REPO / "scripts/removed_unused_params.txt").write_text(
        "\n".join(f"x|{fun}|{param}" for fun, param in methods) + "\n"
    )
    n = strip_calls_for_methods(methods)
    print(f"Stripped {n} call sites")


if __name__ == "__main__":
    main()
