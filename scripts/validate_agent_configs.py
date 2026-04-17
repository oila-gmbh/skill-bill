#!/usr/bin/env python3

from __future__ import annotations

from pathlib import Path
import json
import re
import sys

ROOT_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT_DIR))

from skill_bill.shell_content_contract import (  # noqa: E402
  APPROVED_CODE_REVIEW_AREAS as SHELL_APPROVED_CODE_REVIEW_AREAS,
  SHELL_CONTRACT_VERSION,
  ShellContentContractError,
  discover_platform_packs,
  load_quality_check_content,
)

from skill_repo_contracts import (  # noqa: E402
  ADDON_SUPPORTING_FILE_TARGETS,
  ADDON_DIRECTORY_NAME,
  APPLIED_LEARNINGS_PLACEHOLDER,
  CHILD_METADATA_HANDOFF_RULE,
  CHILD_NO_IMPORT_RULE,
  CHILD_NO_TRIAGE_RULE,
  INLINE_TELEMETRY_CONTRACT_MARKERS,
  ORCHESTRATION_PLAYBOOKS,
  NO_FINDINGS_TRIAGE_RULE,
  PARENT_IMPORT_RULE,
  PARENT_TRIAGE_RULE,
  PORTABLE_REVIEW_SKILLS,
  REVIEW_DELEGATION_REQUIRED_SECTIONS,
  REVIEW_RUN_ID_FORMAT,
  REVIEW_RUN_ID_PLACEHOLDER,
  REVIEW_SESSION_ID_FORMAT,
  REVIEW_SESSION_ID_PLACEHOLDER,
  RISK_REGISTER_FINDING_FORMAT,
  RUNTIME_SUPPORTING_FILES,
  TELEMETERABLE_SKILLS,
  TELEMETRY_OWNERSHIP_HEADING,
  TRIAGE_OWNERSHIP_HEADING,
)


README_TOTAL_PATTERN = re.compile(r"collection of (\d+) AI skills")
README_SECTION_PATTERN = re.compile(r"^### (.+) \((\d+) skills\)$")
README_SKILL_ROW_PATTERN = re.compile(r"^\| `/(bill-[a-z0-9-]+)` \|")
SKILL_REFERENCE_PATTERN = re.compile(r"(?<![A-Za-z0-9.-])(bill-[a-z0-9-]+)(?![A-Za-z0-9-])")
FRONTMATTER_PATTERN = re.compile(r"\A---\n(.*?)\n---\n", re.DOTALL)
SKILL_NAME_PATTERN = re.compile(r"^bill-[a-z0-9-]+$")
ADDON_SLUG_PATTERN = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
PROJECT_OVERRIDES_HEADING = "## Project Overrides"
SKILL_OVERRIDE_FILE = ".agents/skill-overrides.md"
SKILL_OVERRIDE_EXAMPLE_FILE = ".agents/skill-overrides.example.md"
SKILL_OVERRIDE_TITLE = "# Skill Overrides"
SKILL_OVERRIDE_SECTION_PATTERN = re.compile(r"^## (bill-[a-z0-9-]+)$")
# ALLOWED_PACKAGES is discovery-driven: "base" plus every non-hidden directory
# under ``skills/`` and every non-hidden directory under ``platform-packs/``.
# AC9 demands no enumerated platform names survive in the validator, so we
# compute the set at module-import time from the live filesystem layout.
_ROOT_DIR = Path(__file__).resolve().parent.parent


def _discover_allowed_packages(root: Path) -> tuple[str, ...]:
  """Compute the set of package directory names the validator recognizes.

  The rule: "base" is always allowed; every other package name is a live
  directory under ``skills/`` or ``platform-packs/``. The validator keeps
  this discovery-driven so adding a platform pack does not require editing
  the validator (AC9).
  """
  discovered: set[str] = {"base"}
  for container_name in ("skills", "platform-packs"):
    container = root / container_name
    if not container.is_dir():
      continue
    for entry in container.iterdir():
      if not entry.is_dir() or entry.name.startswith("."):
        continue
      discovered.add(entry.name)
  return tuple(sorted(discovered))


ALLOWED_PACKAGES = _discover_allowed_packages(_ROOT_DIR)
# The approved code-review area set is owned by the shell+content contract.
# The validator re-exports it under the historic name so the existing call
# sites continue to compile; platform code-review area enforcement is now
# driven by each platform pack's declared_code_review_areas.
APPROVED_CODE_REVIEW_AREAS = set(SHELL_APPROVED_CODE_REVIEW_AREAS)
EXTERNAL_PLAYBOOK_REFERENCE_PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = (
  (
    re.compile(r"\.bill-shared/orchestration/"),
    "must reference skill-local supporting files instead of install-local playbook paths",
  ),
  (
    re.compile(r"orchestration/(?:stack-routing|review-orchestrator|review-delegation|telemetry-contract|shell-content-contract)/PLAYBOOK\.md"),
    "must reference skill-local supporting files instead of repo-side playbook paths at runtime",
  ),
)
NON_PORTABLE_REVIEW_PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = (
  (
    re.compile(r"`task`"),
    "must not hardcode the `task` tool in shared review orchestration",
  ),
  (
    re.compile(r"\bspawn_agent\b"),
    "must not hardcode the `spawn_agent` tool in shared review orchestration",
  ),
  (
    re.compile(r"\bsub-agent(s)?\b"),
    "must not describe review delegation as sub-agents; use specialist review passes instead",
  ),
  (
    re.compile(r"\bAgent to spawn\b"),
    "must use portable routing-table wording such as 'Specialist review to run'",
  ),
  (
    re.compile(r"\bAgents spawned\b"),
    "must use portable summary wording such as 'Specialist reviews'",
  ),
)
PORTABLE_REVIEW_TELEMETRY_REQUIREMENTS: tuple[tuple[str, str], ...] = (
  (PARENT_IMPORT_RULE, "portable review skills must describe the parent-owned import_review handoff"),
  (CHILD_NO_IMPORT_RULE, "portable review skills must forbid delegated child reviews from calling import_review"),
  (CHILD_METADATA_HANDOFF_RULE, "portable review skills must describe the delegated child metadata handoff"),
  (PARENT_TRIAGE_RULE, "portable review skills must describe the parent-owned triage_findings handoff"),
  (CHILD_NO_TRIAGE_RULE, "portable review skills must forbid delegated child reviews from calling triage_findings"),
  (NO_FINDINGS_TRIAGE_RULE, "portable review skills must define the final parent-owned no-findings triage rule"),
)
REVIEW_ORCHESTRATOR_TELEMETRY_REQUIREMENTS: tuple[tuple[str, str], ...] = (
  (PARENT_IMPORT_RULE, "review orchestration contract must describe the parent-owned import_review handoff"),
  (CHILD_NO_IMPORT_RULE, "review orchestration contract must forbid delegated child reviews from calling import_review"),
  (CHILD_METADATA_HANDOFF_RULE, "review orchestration contract must describe the delegated child metadata handoff"),
  (PARENT_TRIAGE_RULE, "review orchestration contract must describe the parent-owned triage_findings handoff"),
  (CHILD_NO_TRIAGE_RULE, "review orchestration contract must forbid delegated child reviews from calling triage_findings"),
  (NO_FINDINGS_TRIAGE_RULE, "review orchestration contract must define the final parent-owned no-findings triage rule"),
)


def main() -> int:
  root = resolve_root()
  issues: list[str] = []

  skill_files = discover_skill_files(root, issues)
  platform_pack_skill_files = discover_platform_pack_skill_files(root)
  skill_names = sorted(set(skill_files.keys()) | set(platform_pack_skill_files.keys()))
  addon_files = discover_addon_files(root)

  for skill_name, skill_file in skill_files.items():
    validate_skill_file(skill_name, skill_file, issues)
  for skill_name, skill_file in platform_pack_skill_files.items():
    validate_platform_pack_skill_file(skill_name, skill_file, issues)
  for addon_file in addon_files:
    validate_addon_file(addon_file, root, issues)

  validate_readme(root / "README.md", skill_names, issues)
  validate_orchestration_playbooks(root, issues)
  validate_skill_references(root, skill_names, issues)
  validate_orchestrator_passthrough(root, issues)
  validate_skill_override_markdown(
    root / SKILL_OVERRIDE_EXAMPLE_FILE,
    skill_names,
    issues,
    required=True,
  )
  validate_skill_override_markdown(
    root / SKILL_OVERRIDE_FILE,
    skill_names,
    issues,
    required=False,
  )
  validate_plugin_manifest(root / ".claude-plugin" / "plugin.json", issues)
  validate_no_inline_telemetry_contract_drift(root, issues)
  platform_pack_slugs = validate_platform_packs(root, issues)

  if issues:
    print("Agent-config validation failed:")
    for issue in issues:
      print(f"- {issue}")
    return 1

  print("Agent-config validation passed.")
  print(
    f"Validated {len(skill_names)} skills, {len(addon_files)} governed add-on files, "
    f"{len(platform_pack_slugs)} platform packs, "
    "README catalog, skill references, and plugin metadata."
  )
  return 0


def validate_platform_packs(root: Path, issues: list[str]) -> list[str]:
  """Walk ``platform-packs/`` and validate every pack through the loader.

  The loader raises the first loud-fail error it finds per pack; this
  validator surfaces the error text verbatim so operators see the exact
  artifact at fault. Returns the list of slugs successfully loaded so
  subsequent validators can reason about them.
  """
  packs_root = root / "platform-packs"
  if not packs_root.is_dir():
    return []

  slugs: list[str] = []
  for entry in sorted(packs_root.iterdir()):
    if not entry.is_dir() or entry.name.startswith("."):
      continue
    try:
      # Load and validate one pack at a time so one bad pack does not hide
      # validation issues in other packs.
      from skill_bill.shell_content_contract import load_platform_pack
      pack = load_platform_pack(entry)
    except ShellContentContractError as error:
      issues.append(f"platform-packs/{entry.name}: {error}")
      continue

    if pack.contract_version != SHELL_CONTRACT_VERSION:
      issues.append(
        f"platform-packs/{entry.name}: contract_version '{pack.contract_version}' "
        f"does not match shell '{SHELL_CONTRACT_VERSION}'"
      )
      continue

    # Optional declared_quality_check_file: validate it through the
    # dedicated loader so MissingContentFileError and
    # MissingRequiredSectionError surface here verbatim. Discovery is
    # manifest-driven — no platform slugs are enumerated in the check.
    if pack.declared_quality_check_file is not None:
      try:
        load_quality_check_content(pack)
      except ShellContentContractError as error:
        issues.append(f"platform-packs/{entry.name}: {error}")
        continue

    slugs.append(pack.slug)
  return slugs


def resolve_root() -> Path:
  if len(sys.argv) > 2:
    raise SystemExit("Usage: validate_agent_configs.py [repo-root]")
  if len(sys.argv) == 2:
    return Path(sys.argv[1]).resolve()
  return Path(__file__).resolve().parent.parent


def discover_skill_files(root: Path, issues: list[str]) -> dict[str, Path]:
  skills_dir = root / "skills"
  if not skills_dir.is_dir():
    issues.append("skills/ directory is missing")
    return {}

  skill_files: dict[str, Path] = {}
  for skill_file in sorted(skills_dir.rglob("SKILL.md")):
    skill_dir = skill_file.parent
    if not skill_dir.is_dir():
      continue
    if skill_dir.name in skill_files:
      issues.append(
        "Duplicate skill directory name "
        f"'{skill_dir.name}' found at "
        f"{skill_files[skill_dir.name].parent.relative_to(root)} and {skill_dir.relative_to(root)}"
      )
      continue
    skill_files[skill_dir.name] = skill_file

  if not skill_files:
    issues.append("No skills were found under skills/")
  return skill_files


def discover_platform_pack_skill_files(root: Path) -> dict[str, Path]:
  """Enumerate SKILL.md files shipped under platform-packs/.

  Platform packs own code-review content outside ``skills/``; their skill
  directories still ship a SKILL.md that end users invoke by name, so the
  validator must know about them to enforce cross-references and README
  catalog membership.
  """
  packs_root = root / "platform-packs"
  if not packs_root.is_dir():
    return {}

  found: dict[str, Path] = {}
  for skill_file in sorted(packs_root.rglob("SKILL.md")):
    skill_dir = skill_file.parent
    if not skill_dir.is_dir():
      continue
    # Skip anything not named bill-*; platform-packs may host supporting files.
    if not skill_dir.name.startswith("bill-"):
      continue
    found[skill_dir.name] = skill_file
  return found


def validate_platform_pack_skill_file(skill_name: str, skill_file: Path, issues: list[str]) -> None:
  """Lightweight validation for relocated platform-pack skills.

  Platform packs declare their own manifest and section contract via
  ``platform.yaml``; the shell+content loader is the authoritative checker
  there. This routine only enforces the always-applicable pieces that apply
  to every installable SKILL.md: frontmatter shape, declared name matches
  directory, and a description field.
  """
  text = skill_file.read_text(encoding="utf-8")
  frontmatter_match = FRONTMATTER_PATTERN.match(text)
  if not frontmatter_match:
    issues.append(f"{skill_file}: missing YAML frontmatter block")
    return
  frontmatter = parse_frontmatter(frontmatter_match.group(1))
  declared_name = frontmatter.get("name", "")
  description = frontmatter.get("description", "")
  if declared_name != skill_name:
    issues.append(
      f"{skill_file}: frontmatter name '{declared_name}' does not match directory '{skill_name}'"
    )
  if not description:
    issues.append(f"{skill_file}: frontmatter description is missing")
  if not SKILL_NAME_PATTERN.match(skill_name):
    issues.append(
      f"{skill_file}: skill name '{skill_name}' must match an approved bill-* naming pattern"
    )
  validate_runtime_supporting_files(skill_name, text, skill_file, issues)
  validate_portable_review_wording(skill_name, text, skill_file, issues)


def discover_addon_files(root: Path) -> list[Path]:
  skills_dir = root / "skills"
  if not skills_dir.is_dir():
    return []
  return sorted(
    path
    for path in skills_dir.rglob("*.md")
    if ADDON_DIRECTORY_NAME in path.relative_to(skills_dir).parts
  )


ORCHESTRATOR_SKILLS: tuple[tuple[str, tuple[str, ...]], ...] = (
  # (skill_dir, files_to_scan_relative_to_skill_dir)
  ("skills/base/bill-feature-implement", ("SKILL.md", "reference.md")),
  ("skills/base/bill-feature-verify", ("SKILL.md",)),
)
ORCHESTRATED_PASS_THROUGH_MARKER = "orchestrated=true"


def validate_orchestrator_passthrough(root: Path, issues: list[str]) -> None:
  """Each orchestrator skill must instruct the agent to pass orchestrated=true
  to the child MCP tools it invokes, so nested skills don't emit their own
  telemetry events. Missing this makes a workflow silently emit duplicate
  events instead of producing a single rolled-up event.
  """
  for skill_dir_rel, files in ORCHESTRATOR_SKILLS:
    skill_dir = root / skill_dir_rel
    if not skill_dir.exists():
      # Only enforce the rule when the skill exists; do not require its presence.
      # This keeps fixture test repos that don't include orchestrator skills
      # passing without having to seed the orchestrator fixtures they don't use.
      continue
    combined_text = ""
    for file_name in files:
      file_path = skill_dir / file_name
      if file_path.exists():
        combined_text += file_path.read_text(encoding="utf-8") + "\n"
    if ORCHESTRATED_PASS_THROUGH_MARKER not in combined_text.lower():
      issues.append(
        f"{skill_dir}: orchestrator skill must instruct the agent to pass "
        f"'{ORCHESTRATED_PASS_THROUGH_MARKER}' when invoking child telemeterable tools"
      )


def validate_no_inline_telemetry_contract_drift(root: Path, issues: list[str]) -> None:
  """Reject telemeterable skills that contain inline telemetry contract markers.

  Every telemeterable skill should reference the shared telemetry-contract.md
  sidecar instead of embedding shared contract text. This check catches drift
  re-accumulation: a skill that references the sidecar but also contains
  forbidden marker text fails validation.
  """
  for skill_name in TELEMETERABLE_SKILLS:
    skill_dirs = list((root / "skills").rglob(skill_name))
    packs_root = root / "platform-packs"
    if packs_root.is_dir():
      skill_dirs.extend(packs_root.rglob(skill_name))
    # Only consider directories, not file matches.
    skill_dirs = [p for p in skill_dirs if p.is_dir()]
    if not skill_dirs:
      continue
    skill_dir = skill_dirs[0]
    files_to_scan = ["SKILL.md"]
    orchestrator_files = dict(ORCHESTRATOR_SKILLS)
    if str(skill_dir.relative_to(root)) in orchestrator_files:
      for f in orchestrator_files[str(skill_dir.relative_to(root))]:
        if f not in files_to_scan:
          files_to_scan.append(f)
    for file_name in files_to_scan:
      file_path = skill_dir / file_name
      if not file_path.exists():
        continue
      text = file_path.read_text(encoding="utf-8")
      for marker in INLINE_TELEMETRY_CONTRACT_MARKERS:
        if marker in text:
          issues.append(
            f"{file_path.relative_to(root)}: telemeterable skill must not contain inline "
            f"telemetry contract text; found '{marker}'. Use the shared "
            f"telemetry-contract.md sidecar instead."
          )


def validate_skill_file(skill_name: str, skill_file: Path, issues: list[str]) -> None:
  text = skill_file.read_text(encoding="utf-8")
  frontmatter_match = FRONTMATTER_PATTERN.match(text)
  if not frontmatter_match:
    issues.append(f"{skill_file}: missing YAML frontmatter block")
    return

  validate_skill_location(skill_name, skill_file, issues)

  frontmatter = parse_frontmatter(frontmatter_match.group(1))
  declared_name = frontmatter.get("name", "")
  description = frontmatter.get("description", "")

  if declared_name != skill_name:
    issues.append(
      f"{skill_file}: frontmatter name '{declared_name}' does not match directory '{skill_name}'"
    )

  if not description:
    issues.append(f"{skill_file}: frontmatter description is missing")

  if PROJECT_OVERRIDES_HEADING not in text:
    issues.append(f"{skill_file}: missing '{PROJECT_OVERRIDES_HEADING}' section")

  if SKILL_OVERRIDE_FILE not in text:
    issues.append(f"{skill_file}: missing reference to '{SKILL_OVERRIDE_FILE}'")

  validate_runtime_supporting_files(skill_name, text, skill_file, issues)
  validate_portable_review_wording(skill_name, text, skill_file, issues)


def validate_runtime_supporting_files(
  skill_name: str,
  text: str,
  skill_file: Path,
  issues: list[str],
) -> None:
  required_files = RUNTIME_SUPPORTING_FILES.get(skill_name)
  if not required_files:
    return

  for pattern, message in EXTERNAL_PLAYBOOK_REFERENCE_PATTERNS:
    match = pattern.search(text)
    if match:
      issues.append(f"{skill_file}: {message}; found '{match.group(0)}'")

  for file_name in required_files:
    supporting_file = skill_file.parent / file_name
    if (
      skill_name == "bill-feature-implement"
      and file_name in ADDON_SUPPORTING_FILE_TARGETS
    ):
      if file_name not in text and "matching stack-owned add-on supporting files" not in text:
        issues.append(
          f"{skill_file}: must reference local supporting file '{file_name}' or describe stack-owned add-on support-file selection"
        )
    elif file_name not in text:
      issues.append(f"{skill_file}: must reference local supporting file '{file_name}'")
    if not supporting_file.exists():
      issues.append(f"{skill_file}: supporting file '{file_name}' is missing")
    elif not supporting_file.is_symlink():
      issues.append(f"{skill_file}: supporting file '{file_name}' must be a symlink to the shared contract")


def validate_portable_review_wording(
  skill_name: str,
  text: str,
  skill_file: Path,
  issues: list[str],
) -> None:
  if skill_name == "bill-code-review" and REVIEW_SESSION_ID_PLACEHOLDER not in text:
    issues.append(f"{skill_file}: shared code-review router must expose '{REVIEW_SESSION_ID_PLACEHOLDER}'")
  if skill_name == "bill-code-review" and REVIEW_SESSION_ID_FORMAT not in text:
    issues.append(
      f"{skill_file}: shared code-review router must define the review session id format '{REVIEW_SESSION_ID_FORMAT}'"
    )
  if skill_name == "bill-code-review" and REVIEW_RUN_ID_PLACEHOLDER not in text:
    issues.append(f"{skill_file}: shared code-review router must expose '{REVIEW_RUN_ID_PLACEHOLDER}'")
  if skill_name == "bill-code-review" and REVIEW_RUN_ID_FORMAT not in text:
    issues.append(f"{skill_file}: shared code-review router must define the review run id format '{REVIEW_RUN_ID_FORMAT}'")
  if skill_name == "bill-code-review" and APPLIED_LEARNINGS_PLACEHOLDER not in text:
    issues.append(f"{skill_file}: shared code-review router must expose '{APPLIED_LEARNINGS_PLACEHOLDER}'")

  if skill_name not in PORTABLE_REVIEW_SKILLS:
    return

  if REVIEW_SESSION_ID_PLACEHOLDER not in text:
    issues.append(f"{skill_file}: portable review skills must expose '{REVIEW_SESSION_ID_PLACEHOLDER}'")
  if REVIEW_RUN_ID_PLACEHOLDER not in text:
    issues.append(f"{skill_file}: portable review skills must expose '{REVIEW_RUN_ID_PLACEHOLDER}'")
  if APPLIED_LEARNINGS_PLACEHOLDER not in text:
    issues.append(f"{skill_file}: portable review skills must expose '{APPLIED_LEARNINGS_PLACEHOLDER}'")

  for pattern, message in NON_PORTABLE_REVIEW_PATTERNS:
    match = pattern.search(text)
    if match:
      issues.append(f"{skill_file}: {message}; found '{match.group(0)}'")


def validate_orchestration_playbooks(root: Path, issues: list[str]) -> None:
  for playbook_name, relative_path in ORCHESTRATION_PLAYBOOKS.items():
    playbook_path = root / relative_path
    if not playbook_path.is_file():
      issues.append(f"{relative_path} is missing")
      continue

    text = playbook_path.read_text(encoding="utf-8")
    if not FRONTMATTER_PATTERN.match(text):
      issues.append(f"{relative_path}: missing YAML frontmatter block")
    if SKILL_OVERRIDE_FILE in text:
      issues.append(f"{relative_path}: orchestration playbooks must not reference '{SKILL_OVERRIDE_FILE}'")
    if playbook_name == "review-delegation":
      for section in REVIEW_DELEGATION_REQUIRED_SECTIONS:
        if section not in text:
          issues.append(f"{relative_path}: missing required delegation section '{section}'")
    if playbook_name == "review-orchestrator":
      if REVIEW_SESSION_ID_PLACEHOLDER not in text:
        issues.append(
          f"{relative_path}: review orchestration contract must expose '{REVIEW_SESSION_ID_PLACEHOLDER}'"
        )
      if REVIEW_SESSION_ID_FORMAT not in text:
        issues.append(
          f"{relative_path}: review orchestration contract must define the review session id format '{REVIEW_SESSION_ID_FORMAT}'"
        )
      if REVIEW_RUN_ID_PLACEHOLDER not in text:
        issues.append(
          f"{relative_path}: review orchestration contract must expose '{REVIEW_RUN_ID_PLACEHOLDER}'"
        )
      if REVIEW_RUN_ID_FORMAT not in text:
        issues.append(
          f"{relative_path}: review orchestration contract must define the review run id format '{REVIEW_RUN_ID_FORMAT}'"
        )
      if APPLIED_LEARNINGS_PLACEHOLDER not in text:
        issues.append(
          f"{relative_path}: review orchestration contract must expose '{APPLIED_LEARNINGS_PLACEHOLDER}'"
        )
      if RISK_REGISTER_FINDING_FORMAT not in text:
        issues.append(
          f"{relative_path}: review orchestration contract must define machine-readable findings as '{RISK_REGISTER_FINDING_FORMAT}'"
        )
      require_markdown_heading(
        text,
        TELEMETRY_OWNERSHIP_HEADING,
        f"{relative_path}: review orchestration contract must define the telemetry ownership section as a markdown heading",
        issues,
      )
      require_markdown_heading(
        text,
        TRIAGE_OWNERSHIP_HEADING,
        f"{relative_path}: review orchestration contract must define the triage ownership section as a markdown heading",
        issues,
      )
    if playbook_name == "telemetry-contract":
      require_markdown_heading(
        text,
        TELEMETRY_OWNERSHIP_HEADING,
        f"{relative_path}: telemetry contract must define the telemetry ownership section as a markdown heading",
        issues,
      )
      require_markdown_heading(
        text,
        TRIAGE_OWNERSHIP_HEADING,
        f"{relative_path}: telemetry contract must define the triage ownership section as a markdown heading",
        issues,
      )
      for required_text, message in PORTABLE_REVIEW_TELEMETRY_REQUIREMENTS:
        if required_text not in text:
          issues.append(f"{relative_path}: {message}; missing '{required_text}'")
      for required_text, message in REVIEW_ORCHESTRATOR_TELEMETRY_REQUIREMENTS:
        if required_text not in text:
          issues.append(f"{relative_path}: {message}; missing '{required_text}'")


def require_markdown_heading(text: str, heading: str, message: str, issues: list[str]) -> None:
  if not re.search(rf"(?m)^#{{2,6}} {re.escape(heading)}$", text):
    issues.append(message)


def validate_addon_file(addon_file: Path, root: Path, issues: list[str]) -> None:
  skills_dir = root / "skills"
  relative_path = addon_file.relative_to(skills_dir)
  parts = relative_path.parts

  if len(parts) != 3:
    issues.append(
      f"{addon_file}: expected add-on path format skills/<package>/{ADDON_DIRECTORY_NAME}/<addon-file>.md, "
      f"got skills/{relative_path}"
    )
    return

  package_name, directory_name, file_name = parts
  if directory_name != ADDON_DIRECTORY_NAME:
    issues.append(
      f"{addon_file}: governed add-ons must live under skills/<package>/{ADDON_DIRECTORY_NAME}/"
    )
    return

  if package_name == "base":
    issues.append(f"{addon_file}: governed add-ons must be stack-owned, not placed under skills/base/")
    return

  if package_name not in ALLOWED_PACKAGES:
    issues.append(
      f"{addon_file}: add-on package '{package_name}' is not allowed; use one of "
      f"{', '.join(package for package in ALLOWED_PACKAGES if package != 'base')}"
    )
    return

  if not file_name.endswith(".md"):
    issues.append(f"{addon_file}: add-on file '{file_name}' must end with '.md'")
    return

  addon_name = file_name.removesuffix(".md")
  if not ADDON_SLUG_PATTERN.match(addon_name):
    issues.append(
      f"{addon_file}: add-on file '{file_name}' must use lowercase kebab-case"
    )


def validate_skill_location(skill_name: str, skill_file: Path, issues: list[str]) -> None:
  skills_dir = skill_file.parents[2]
  relative_path = skill_file.relative_to(skills_dir)
  parts = relative_path.parts

  if len(parts) != 3:
    issues.append(
      f"{skill_file}: expected path format skills/<package>/<skill>/SKILL.md, got skills/{relative_path}"
    )
    return

  package_name, directory_name, file_name = parts
  if file_name != "SKILL.md":
    issues.append(f"{skill_file}: expected skill file to be named SKILL.md")

  if directory_name != skill_name:
    issues.append(
      f"{skill_file}: directory '{directory_name}' does not match discovered skill name '{skill_name}'"
    )

  if not SKILL_NAME_PATTERN.match(skill_name):
    issues.append(
      f"{skill_file}: skill name '{skill_name}' must match an approved bill-* naming pattern"
    )

  if package_name not in ALLOWED_PACKAGES:
    issues.append(
      f"{skill_file}: package '{package_name}' is not allowed; use one of {', '.join(ALLOWED_PACKAGES)}"
    )
    return

  expected_prefixes = expected_prefixes_for_package(package_name)
  if package_name == "base":
    if any(skill_name.startswith(prefix) for prefix in ("bill-agent-config-", "bill-kotlin-", "bill-kmp-", "bill-backend-kotlin-", "bill-php-", "bill-go-", "bill-gradle-")):
      issues.append(
        f"{skill_file}: base skills must use neutral names; move '{skill_name}' to the matching package"
      )
    return

  if not any(skill_name.startswith(prefix) for prefix in expected_prefixes):
    issues.append(
      f"{skill_file}: skill '{skill_name}' must live under skills/{package_name}/ and start with "
      + " or ".join(f"'{prefix}'" for prefix in expected_prefixes)
    )
    return

  validate_platform_skill_name(
    package_name,
    skill_name,
    skill_file,
    base_capabilities_for_skills_dir(skills_dir),
    issues,
  )


def validate_platform_skill_name(
  package_name: str,
  skill_name: str,
  skill_file: Path,
  base_capabilities: set[str],
  issues: list[str],
) -> None:
  prefix = expected_prefixes_for_package(package_name)[0]
  capability = skill_name.removeprefix(prefix)

  if capability in base_capabilities:
    return

  if capability.startswith("code-review-"):
    area = capability.removeprefix("code-review-")
    if area in APPROVED_CODE_REVIEW_AREAS:
      return
    issues.append(
      f"{skill_file}: code-review specialization '{area}' is not approved; "
      f"use one of {', '.join(sorted(APPROVED_CODE_REVIEW_AREAS))}"
    )
    return

  issues.append(
    f"{skill_file}: platform skill '{skill_name}' must either override an approved base skill "
    "using bill-<platform>-<base-capability> or use an approved code-review specialization "
    "using bill-<platform>-code-review-<area>"
  )


def base_capabilities_for_skills_dir(skills_dir: Path) -> set[str]:
  base_dir = skills_dir / "base"
  if not base_dir.is_dir():
    return set()

  capabilities: set[str] = set()
  for skill_dir in base_dir.iterdir():
    if skill_dir.is_dir() and skill_dir.name.startswith("bill-"):
      capabilities.add(skill_dir.name.removeprefix("bill-"))
  return capabilities


def expected_prefixes_for_package(package_name: str) -> tuple[str, ...]:
  if package_name == "base":
    return ()
  return (f"bill-{package_name}-",)


def parse_frontmatter(block: str) -> dict[str, str]:
  values: dict[str, str] = {}
  for line in block.splitlines():
    if ":" not in line:
      continue
    key, value = line.split(":", 1)
    values[key.strip()] = value.strip()
  return values


def validate_readme(readme_path: Path, skill_names: list[str], issues: list[str]) -> None:
  text = readme_path.read_text(encoding="utf-8")
  total_match = README_TOTAL_PATTERN.search(text)
  if not total_match:
    issues.append("README.md: total skill count line is missing")
  else:
    declared_total = int(total_match.group(1))
    actual_total = len(skill_names)
    if declared_total != actual_total:
      issues.append(
        f"README.md: declares {declared_total} skills, but skills/ contains {actual_total}"
      )

  documented_skills: list[str] = []
  lines = text.splitlines()
  current_section = ""
  expected_count = 0
  section_count = 0

  for line in lines + ["### END (0 skills)"]:
    section_match = README_SECTION_PATTERN.match(line)
    if section_match:
      if current_section and section_count != expected_count:
        issues.append(
          f"README.md: section '{current_section}' declares {expected_count} skills but lists {section_count}"
        )
      current_section = section_match.group(1)
      expected_count = int(section_match.group(2))
      section_count = 0
      continue

    row_match = README_SKILL_ROW_PATTERN.match(line)
    if row_match:
      documented_skills.append(row_match.group(1))
      section_count += 1

  documented_set = sorted(set(documented_skills))
  actual_set = sorted(skill_names)

  missing_from_readme = sorted(set(actual_set) - set(documented_set))
  extra_in_readme = sorted(set(documented_set) - set(actual_set))

  if missing_from_readme:
    issues.append(
      "README.md: missing skills in catalog: " + ", ".join(missing_from_readme)
    )
  if extra_in_readme:
    issues.append(
      "README.md: documents unknown skills: " + ", ".join(extra_in_readme)
    )


def validate_skill_references(root: Path, skill_names: list[str], issues: list[str]) -> None:
  known_skills = set(skill_names)
  files_to_scan = sorted((root / "skills").rglob("SKILL.md"))
  platform_packs_dir = root / "platform-packs"
  if platform_packs_dir.is_dir():
    files_to_scan.extend(sorted(platform_packs_dir.rglob("SKILL.md")))

  for file_path in files_to_scan:
    text = file_path.read_text(encoding="utf-8")
    for reference in sorted(set(SKILL_REFERENCE_PATTERN.findall(text))):
      if reference not in known_skills:
        relative_path = file_path.relative_to(root)
        issues.append(f"{relative_path}: references unknown skill '{reference}'")


def validate_skill_override_markdown(
  file_path: Path,
  skill_names: list[str],
  issues: list[str],
  *,
  required: bool,
) -> None:
  if not file_path.exists():
    if required:
      issues.append(f"{file_path.relative_to(file_path.parent.parent)} is missing")
    return

  relative_path = file_path.relative_to(file_path.parent.parent)
  lines = file_path.read_text(encoding="utf-8").splitlines()
  known_skills = set(skill_names)
  seen_sections: set[str] = set()
  current_section: str | None = None
  current_section_has_bullet = False
  saw_title = False
  saw_any_section = False

  for index, line in enumerate(lines, start=1):
    stripped = line.strip()
    if not stripped:
      continue

    if stripped == SKILL_OVERRIDE_TITLE:
      if saw_title or current_section is not None or index != 1:
        issues.append(
          f"{relative_path}:{index}: '{SKILL_OVERRIDE_TITLE}' must appear exactly once as the first line"
        )
      saw_title = True
      continue

    section_match = SKILL_OVERRIDE_SECTION_PATTERN.match(stripped)
    if section_match:
      if not saw_title:
        issues.append(f"{relative_path}:{index}: missing '{SKILL_OVERRIDE_TITLE}' before sections")
      if current_section is not None and not current_section_has_bullet:
        issues.append(
          f"{relative_path}:{index - 1}: section '{current_section}' must contain at least one bullet item"
        )

      section_name = section_match.group(1)
      if section_name not in known_skills:
        issues.append(
          f"{relative_path}:{index}: section '{section_name}' is not an existing skill name"
        )
      if section_name in seen_sections:
        issues.append(f"{relative_path}:{index}: duplicate section '{section_name}'")

      seen_sections.add(section_name)
      current_section = section_name
      current_section_has_bullet = False
      saw_any_section = True
      continue

    if stripped.startswith("#"):
      issues.append(
        f"{relative_path}:{index}: only '{SKILL_OVERRIDE_TITLE}' and '## bill-...' headings are allowed"
      )
      continue

    if current_section is None:
      issues.append(
        f"{relative_path}:{index}: freeform text is not allowed outside a '## bill-...' section"
      )
      continue

    if line.startswith("- "):
      current_section_has_bullet = True
      continue

    if line.startswith("  ") and current_section_has_bullet:
      continue

    issues.append(
      f"{relative_path}:{index}: section '{current_section}' must use bullet items; freeform text is not allowed"
    )

  if not saw_title:
    issues.append(f"{relative_path}: missing '{SKILL_OVERRIDE_TITLE}' title")

  if current_section is not None and not current_section_has_bullet:
    issues.append(
      f"{relative_path}: section '{current_section}' must contain at least one bullet item"
    )

  if not saw_any_section:
    issues.append(f"{relative_path}: must contain at least one '## bill-...' section")


def validate_plugin_manifest(plugin_path: Path, issues: list[str]) -> None:
  try:
    content = plugin_path.read_text(encoding="utf-8")
    data = json.loads(content)
  except FileNotFoundError:
    issues.append(".claude-plugin/plugin.json is missing")
    return
  except json.JSONDecodeError as error:
    issues.append(f".claude-plugin/plugin.json: invalid JSON ({error})")
    return

  name = data.get("name")
  description = data.get("description")
  keywords = data.get("keywords")

  if not isinstance(name, str) or not name.strip():
    issues.append(".claude-plugin/plugin.json: name must be a non-empty string")
  if not isinstance(description, str) or not description.strip():
    issues.append(".claude-plugin/plugin.json: description must be a non-empty string")
  if not isinstance(keywords, list) or not keywords:
    issues.append(".claude-plugin/plugin.json: keywords must be a non-empty array")


if __name__ == "__main__":
  raise SystemExit(main())
