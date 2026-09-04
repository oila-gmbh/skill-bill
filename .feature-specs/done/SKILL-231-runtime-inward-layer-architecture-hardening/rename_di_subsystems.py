#!/usr/bin/env python3
import os
import re
from pathlib import Path

ROOT = Path("/home/sermilion/StudioProjects/skill-bill/runtime-kotlin/runtime-core/src/main/kotlin/skillbill/di")

BINDINGS_MAP = {
    "RuntimeComponentBindingsA1": "RuntimeBootstrapBindings",
    "RuntimeComponentBindingsA2": "RuntimeTelemetryInstallBindings",
    "RuntimeComponentBindingsA3": "RuntimeInstallNativeAgentBindings",
    "RuntimeComponentBindingsA4": "RuntimeLauncherGoalRunnerBindings",
    "RuntimeComponentBindingsA5": "RuntimeGoalRunnerDiagnosticsBindings",
    "RuntimeComponentBindingsA6": "RuntimeGoalRunnerPersistenceReviewBindings",
    "RuntimeComponentBindingsA7": "RuntimeInstallScaffoldBindings",
    "RuntimeComponentBindingsB1": "RuntimeScaffoldPipelineBindings",
    "RuntimeComponentBindingsB2": "RuntimeWorkflowReviewEvidenceBindings",
    "RuntimeComponentBindingsB3": "RuntimeReviewFeatureTaskAgentAddonBindings",
    "RuntimeComponentBindingsB4": "RuntimeWorkflowInstallStoreBindings",
    "RuntimeComponentBindingsB5": "RuntimeFeatureTaskRuntimeValidatorBindings",
    "RuntimeComponentBindingsB6": "RuntimeGoalReviewValidatorBindings",
    "RuntimeComponentBindingsB7": "RuntimeFeatureTaskReviewIntegrationBindings",
}

PROVIDES_MAP = {
    "RuntimeComponentProvides1": "RuntimeTelemetryInstallProvides",
    "RuntimeComponentProvides2": "RuntimeInstallLauncherProvides",
    "RuntimeComponentProvides3": "RuntimeGoalRunnerPlanningProvides",
    "RuntimeComponentProvides4": "RuntimeDiagnosticsReviewProvides",
    "RuntimeComponentProvides5": "RuntimeGoalRunnerScaffoldProvides",
    "RuntimeComponentProvides6": "RuntimeScaffoldWorkflowProvides",
    "RuntimeComponentProvides7": "RuntimeReviewWorkflowProvides",
    "RuntimeComponentProvides8": "RuntimeWorkflowValidatorProvides",
    "RuntimeComponentProvides9": "RuntimeFeatureTaskGoalValidatorProvides",
    "RuntimeComponentProvides10": "RuntimeCompositionMiscProvides",
    "RuntimeComponentProvides11": "RuntimeGoalRunnerWorkflowProvides",
    "RuntimeComponentProvides12": "RuntimeGoalRunnerBoundaryProvides",
    "RuntimeComponentProvides13": "RuntimeReviewFeatureTaskGateProvides",
}

ALL_MAP = {**BINDINGS_MAP, **PROVIDES_MAP}


def rename_files():
    for old, new in ALL_MAP.items():
        old_path = ROOT / f"{old}.kt"
        new_path = ROOT / f"{new}.kt"
        if old_path.exists():
            old_path.rename(new_path)
            print(f"renamed {old}.kt -> {new}.kt")


def replace_in_tree(base: Path):
    for path in base.rglob("*"):
        if not path.is_file() or path.suffix not in {".kt", ".md", ".txt"}:
            continue
        text = path.read_text()
        original = text
        for old, new in ALL_MAP.items():
            text = text.replace(old, new)
        if text != original:
            path.write_text(text)
            print(f"updated {path}")


if __name__ == "__main__":
    rename_files()
    replace_in_tree(Path("/home/sermilion/StudioProjects/skill-bill/runtime-kotlin"))
