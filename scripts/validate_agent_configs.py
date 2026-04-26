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
  CANONICAL_SKILL_MD_BANLIST_PATTERNS,
  CANONICAL_SKILL_MD_FRONTMATTER_ALLOWED_KEYS,
  CEREMONY_FREE_FORM_H2S,
  InvalidSkillMdShapeError,
  REQUIRED_GOVERNED_SECTIONS,
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
  TELEMETRY_OWNERSHIP_HEADING,
  TRIAGE_OWNERSHIP_HEADING,
  required_supporting_files_for_skill,
)


README_SECTION_PATTERN = re.compile(r"^### (.+) \((\d+) skills\)$")
README_SKILL_ROW_PATTERN = re.compile(r"^\| `/(bill-[a-z0-9-]+)` \|")
SKILL_REFERENCE_PATTERN = re.compile(r"(?<![A-Za-z0-9.-])(bill-[a-z0-9-]+)(?![A-Za-z0-9-])")
FRONTMATTER_PATTERN = re.compile(r"\A---\n(.*?)\n---\n", re.DOTALL)
SKILL_NAME_PATTERN = re.compile(r"^bill-[a-z0-9-]+$")
ADDON_SLUG_PATTERN = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
SKILL_OVERRIDE_FILE = ".agents/skill-overrides.md"
SKILL_OVERRIDE_EXAMPLE_FILE = ".agents/skill-overrides.example.md"
SKILL_OVERRIDE_TITLE = "# Skill Overrides"
SKILL_OVERRIDE_SECTION_PATTERN = re.compile(r"^## (bill-[a-z0-9-]+)$")
# ALLOWED_PACKAGES is discovery-driven: the virtual ``base`` package plus every
# non-hidden platform/package directory that already exists under ``skills/`` or
# ``platform-packs/``.
# Newly scaffolded pre-shell stacks live only under ``skills/<platform>/...``
# until they are piloted, so the validator must not require a matching
# ``platform-packs/<platform>/`` root for them.
_ROOT_DIR = Path(__file__).resolve().parent.parent


def _discover_allowed_packages(root: Path) -> tuple[str, ...]:
  """Compute the set of package directory names the validator recognizes.

  The rule: ``base`` is always allowed as the virtual package for canonical
  root-level skills under ``skills/bill-...``. Every other package name is a
  live top-level platform directory under ``skills/`` or ``platform-packs/``.
  This keeps validation aligned with the scaffolded filesystem layout and
  avoids hard-coded legacy package lists.
  """
  discovered: set[str] = {"base"}
  for container_name in ("skills", "platform-packs"):
    container = root / container_name
    if not container.is_dir():
      continue
    for entry in container.iterdir():
      if (
        not entry.is_dir()
        or entry.name.startswith(".")
        or (container_name == "skills" and entry.name.startswith("bill-"))
      ):
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
FRAMEWORK_DUPLICATION_LINES: tuple[str, ...] = (
  "## Additional Resources",
  "## Output Rules",
  "## Review Output",
  "## Output Format",
  "### Telemetry",
  "### Implementation Mode Notes",
)
SYSTEM_OWNED_CONTENT_MARKERS: tuple[str, ...] = (
  "## Setup",
)
EXECUTION_AND_REPORTING_CEREMONY_MARKERS: tuple[str, ...] = (
  "shared execution-mode contract",
  "If execution mode is `inline`:",
  "If execution mode is `delegated`:",
  "delegated subagent",
  "wrapper-linked sidecars",
  "Selected add-ons: none",
  "When reporting results:",
  "show issue count by category",
  "report each fix with `file:line`",
  "display the final `./gradlew check` result",
)
UNRESOLVED_PLACEHOLDER_PATTERN = re.compile(r"(?m)^\s*(?:[-*]\s*)?(?:TODO|FIXME)\b")

CANONICAL_SKILL_MD_LEGACY_MARKERS: tuple[str, ...] = ()

CANONICAL_SKILL_MD_ENFORCEMENT_DISABLED = False
CANONICAL_SKILL_MD_FORCE_SKIP_DIRECTORIES: tuple[str, ...] = (
  "skills/bill-editorial-assignment-desk",
)


def validate_skill_md_shape(skill_md_path: Path, *, enforce: bool = True) -> None:
  """Enforce the canonical SKILL.md shape on a single file.

  The canonical shape is: a frontmatter block whose keys are restricted to
  :data:`CANONICAL_SKILL_MD_FRONTMATTER_ALLOWED_KEYS`, immediately followed by
  three required H2 sections — ``## Descriptor``, ``## Execution``,
  ``## Ceremony`` — in that order. The body must contain no other H2, no H1,
  no H3+, no intro paragraph before the first H2, and none of the patterns
  enumerated in :data:`CANONICAL_SKILL_MD_BANLIST_PATTERNS`.

  When ``enforce`` is ``False`` and the file still carries any legacy-shape
  markers from :data:`CANONICAL_SKILL_MD_LEGACY_MARKERS`, the file is skipped
  rather than rejected. This guard is removed once every in-scope SKILL.md has
  been migrated to the canonical shape.
  """
  text = skill_md_path.read_text(encoding="utf-8")
  if not enforce:
    for marker in CANONICAL_SKILL_MD_LEGACY_MARKERS:
      if marker in text:
        return

  frontmatter_match = FRONTMATTER_PATTERN.match(text)
  if not frontmatter_match:
    raise InvalidSkillMdShapeError(
      f"{skill_md_path}: SKILL.md must begin with a YAML frontmatter block."
    )

  frontmatter = parse_frontmatter(frontmatter_match.group(1))
  unknown_keys = sorted(set(frontmatter.keys()) - CANONICAL_SKILL_MD_FRONTMATTER_ALLOWED_KEYS)
  if unknown_keys:
    raise InvalidSkillMdShapeError(
      f"{skill_md_path}: SKILL.md frontmatter contains disallowed keys "
      f"{unknown_keys}; only {sorted(CANONICAL_SKILL_MD_FRONTMATTER_ALLOWED_KEYS)} are allowed."
    )
  for required_key in ("name", "description"):
    if required_key not in frontmatter or not frontmatter[required_key]:
      raise InvalidSkillMdShapeError(
        f"{skill_md_path}: SKILL.md frontmatter is missing required key '{required_key}'."
      )

  body = text[frontmatter_match.end():]
  body_lines = body.splitlines()

  fm_line_count = text[: frontmatter_match.end()].count("\n")
  body_start_line = fm_line_count + 1

  in_fence = False
  found_first_h2 = False
  intro_seen = False
  h2_sequence: list[tuple[int, str]] = []

  fence_pattern = re.compile(r"^\s*(?:```|~~~)")

  for offset, raw_line in enumerate(body_lines):
    file_line_no = body_start_line + offset
    line = raw_line

    if fence_pattern.match(line):
      raise InvalidSkillMdShapeError(
        f"{skill_md_path}:{file_line_no}: fenced code blocks are not allowed in SKILL.md."
      )

    stripped = line.strip()
    if line.startswith("## "):
      h2_sequence.append((file_line_no, stripped))
      found_first_h2 = True
      continue

    if not found_first_h2:
      if stripped:
        intro_seen = True
        raise InvalidSkillMdShapeError(
          f"{skill_md_path}:{file_line_no}: intro paragraph or content is not allowed before the first H2."
        )
      continue

    for label, pattern in CANONICAL_SKILL_MD_BANLIST_PATTERNS:
      if pattern.match(line):
        raise InvalidSkillMdShapeError(
          f"{skill_md_path}:{file_line_no}: SKILL.md body must not contain {label}; matched '{line.rstrip()}'."
        )

  if not h2_sequence:
    raise InvalidSkillMdShapeError(
      f"{skill_md_path}: SKILL.md must contain the canonical H2 sections "
      f"{list(REQUIRED_GOVERNED_SECTIONS)}."
    )

  observed_headings = [heading for _, heading in h2_sequence]
  expected_headings = list(REQUIRED_GOVERNED_SECTIONS)
  if observed_headings != expected_headings:
    raise InvalidSkillMdShapeError(
      f"{skill_md_path}: SKILL.md must contain exactly the H2 sections "
      f"{expected_headings} in that order; got {observed_headings}."
    )

  del intro_seen  # silence linter; the loop already raises on intro content


def collect_skill_md_shape_issues(root: Path, issues: list[str]) -> None:
  """Walk every SKILL.md in skills/ and platform-packs/ and validate shape."""
  candidate_files: list[Path] = []
  for container in ("skills", "platform-packs"):
    container_dir = root / container
    if not container_dir.is_dir():
      continue
    candidate_files.extend(sorted(container_dir.rglob("SKILL.md")))

  enforce_globally = not CANONICAL_SKILL_MD_ENFORCEMENT_DISABLED
  for skill_md_path in candidate_files:
    relative = str(skill_md_path.parent.relative_to(root))
    if any(relative == skip or relative.startswith(skip + "/") for skip in CANONICAL_SKILL_MD_FORCE_SKIP_DIRECTORIES):
      continue
    try:
      validate_skill_md_shape(skill_md_path, enforce=enforce_globally)
    except InvalidSkillMdShapeError as error:
      issues.append(str(error))


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

  validate_readme(
    root / "README.md",
    skill_files,
    sorted(platform_pack_skill_files.keys()),
    issues,
  )
  validate_orchestration_playbooks(root, issues)
  validate_skill_references(root, skill_names, issues)
  validate_orchestrator_passthrough(root, issues)
  validate_workflow_driven_skills(root, issues)
  validate_feature_implement_shell_contract(root, issues)
  validate_feature_verify_shell_contract(root, issues)
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
  collect_skill_md_shape_issues(root, issues)
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
  validate_governed_content_file(skill_name, skill_file, issues)
  try:
    validate_skill_md_shape(skill_file)
  except InvalidSkillMdShapeError as error:
    issues.append(str(error))


def validate_governed_content_file(
  skill_name: str,
  skill_file: Path,
  issues: list[str],
) -> None:
  content_file = skill_file.parent / "content.md"
  if not content_file.is_file():
    return

  text = content_file.read_text(encoding="utf-8")
  stripped = text.strip()
  if not stripped:
    issues.append(f"{content_file}: content.md must not be empty")
    return

  visible_lines = [line.strip() for line in text.splitlines() if line.strip()]
  if len(visible_lines) <= 1:
    issues.append(
      f"{content_file}: content.md must include authored guidance beyond the title heading"
    )

  if UNRESOLVED_PLACEHOLDER_PATTERN.search(text):
    issues.append(
      f"{content_file}: content.md contains an unresolved TODO/FIXME placeholder"
    )

  for heading in CEREMONY_FREE_FORM_H2S:
    if heading in text.splitlines():
      issues.append(
        f"{content_file}: content.md must not reintroduce shell-owned heading '{heading}'"
      )

  for marker in FRAMEWORK_DUPLICATION_LINES:
    if marker in text.splitlines():
      issues.append(
        f"{content_file}: content.md must not inline shared review-contract block '{marker}'"
      )

  for marker in SYSTEM_OWNED_CONTENT_MARKERS:
    if marker in text:
      issues.append(
        f"{content_file}: content.md must not inline system-owned setup contract marker '{marker}'"
      )

  for marker in EXECUTION_AND_REPORTING_CEREMONY_MARKERS:
    if marker in text:
      issues.append(
        f"{content_file}: content.md must not inline shell-owned execution/reporting marker '{marker}'"
      )

  required_files = required_supporting_files_for_skill(skill_name)
  for file_name in required_files:
    if file_name in ADDON_SUPPORTING_FILE_TARGETS:
      continue
    if file_name in text:
      issues.append(
        f"{content_file}: content.md must not carry system-required supporting file reference '{file_name}'"
      )


def discover_addon_files(root: Path) -> list[Path]:
  discovered: list[Path] = []
  for container_name in ("skills", "platform-packs"):
    container = root / container_name
    if not container.is_dir():
      continue
    discovered.extend(
      path
      for path in container.rglob("*.md")
      if ADDON_DIRECTORY_NAME in path.relative_to(container).parts
    )
  return sorted(discovered)


ORCHESTRATOR_SKILLS: tuple[tuple[str, tuple[str, ...]], ...] = (
  # (skill_dir, files_to_scan_relative_to_skill_dir)
  ("skills/bill-feature-implement", ("SKILL.md", "content.md", "reference.md")),
  ("skills/bill-feature-verify", ("SKILL.md", "content.md")),
)
ORCHESTRATED_PASS_THROUGH_MARKER = "orchestrated=true"
WORKFLOW_DRIVEN_SKILLS: tuple[tuple[str, tuple[str, ...], tuple[str, ...]], ...] = (
  (
    "skills/bill-feature-implement",
    ("content.md", "reference.md"),
    (
      "Step id: `assess`",
      "Step id: `implement`",
      "Step id: `pr_description`",
      "feature_implement_workflow_open",
      "feature_implement_workflow_update",
      "feature_implement_workflow_continue",
      "`assessment`",
      "`preplan_digest`",
      "`implementation_summary`",
      "`pr_result`",
    ),
  ),
  (
    "skills/bill-feature-verify",
    ("content.md",),
    (
      "Step id: `collect_inputs`",
      "Step id: `code_review`",
      "Step id: `verdict`",
      "feature_verify_workflow_open",
      "feature_verify_workflow_update",
      "feature_verify_workflow_get",
      "feature_verify_workflow_continue",
      "`input_context`",
      "`criteria_summary`",
      "`diff_summary`",
      "`review_result`",
      "`completeness_audit_result`",
      "`verdict_result`",
    ),
  ),
)
FEATURE_IMPLEMENT_SHELL_REQUIRED_MARKERS: tuple[str, ...] = (
  "Step id: `assess`",
  "Step id: `create_branch`",
  "Step id: `preplan`",
  "Step id: `plan`",
  "Step id: `implement`",
  "Step id: `review`",
  "Step id: `audit`",
  "Step id: `validate`",
  "Step id: `write_history`",
  "Step id: `commit_push`",
  "Step id: `pr_description`",
  "feature_implement_workflow_open",
  "feature_implement_workflow_update",
  "feature_implement_workflow_continue",
  "`assessment`",
  "`branch`",
  "`preplan_digest`",
  "`plan`",
  "`implementation_summary`",
  "`review_result`",
  "`audit_report`",
  "`validation_result`",
  "`history_result`",
  "`commit_push_result`",
  "`pr_result`",
)


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


def validate_workflow_driven_skills(root: Path, issues: list[str]) -> None:
  """Top-level workflow skills must retain their workflow-runtime contract."""
  for skill_dir_rel, files, required_markers in WORKFLOW_DRIVEN_SKILLS:
    skill_dir = root / skill_dir_rel
    if not skill_dir.exists():
      continue

    combined_text = ""
    for file_name in files:
      file_path = skill_dir / file_name
      if file_path.exists():
        combined_text += file_path.read_text(encoding="utf-8") + "\n"

    for marker in required_markers:
      if marker not in combined_text:
        issues.append(
          f"{skill_dir}: workflow-driven skill must include '{marker}'"
        )


def validate_feature_implement_shell_contract(root: Path, issues: list[str]) -> None:
  """Verify ``bill-feature-implement``'s workflow contract surface.

  Step prose, artifact names, and install gates live in ``content.md`` after
  SKILL-31. The validator cross-checks every step id against
  :data:`FEATURE_IMPLEMENT_WORKFLOW_STEP_IDS` and every artifact name against
  :data:`FEATURE_IMPLEMENT_WORKFLOW_ARTIFACT_NAMES` from ``skill_bill.constants``.
  """
  skill_dir = root / "skills" / "bill-feature-implement"
  if not skill_dir.exists():
    return

  skill_file = skill_dir / "SKILL.md"
  content_file = skill_dir / "content.md"
  if not skill_file.is_file():
    return

  if not content_file.is_file():
    issues.append(f"{skill_dir}: bill-feature-implement must include sibling content.md")
    return

  from skill_bill.constants import (
    FEATURE_IMPLEMENT_WORKFLOW_ARTIFACT_NAMES,
    FEATURE_IMPLEMENT_WORKFLOW_STEP_IDS,
  )

  content_text = content_file.read_text(encoding="utf-8")
  for marker in FEATURE_IMPLEMENT_SHELL_REQUIRED_MARKERS:
    if marker not in content_text:
      issues.append(
        f"{content_file}: bill-feature-implement content must include '{marker}'"
      )

  for step_id in FEATURE_IMPLEMENT_WORKFLOW_STEP_IDS:
    if step_id == "finish":
      continue
    binding = f"Step id: `{step_id}`"
    if binding not in content_text:
      issues.append(
        f"{content_file}: bill-feature-implement content must bind step '{step_id}' "
        f"with the inline marker '{binding}'"
      )

  for artifact_name in FEATURE_IMPLEMENT_WORKFLOW_ARTIFACT_NAMES:
    if f"`{artifact_name}`" not in content_text:
      issues.append(
        f"{content_file}: bill-feature-implement content must reference artifact '`{artifact_name}`'"
      )


def validate_feature_verify_shell_contract(root: Path, issues: list[str]) -> None:
  """Verify ``bill-feature-verify``'s workflow contract surface.

  Step prose and artifact names live in ``content.md`` after SKILL-31. The
  validator cross-checks every step id against
  :data:`FEATURE_VERIFY_WORKFLOW_STEP_IDS` and every artifact name against
  :data:`FEATURE_VERIFY_WORKFLOW_ARTIFACT_NAMES` from ``skill_bill.constants``.
  """
  skill_dir = root / "skills" / "bill-feature-verify"
  if not skill_dir.exists():
    return

  skill_file = skill_dir / "SKILL.md"
  content_file = skill_dir / "content.md"
  if not skill_file.is_file():
    return

  if not content_file.is_file():
    issues.append(f"{skill_dir}: bill-feature-verify must include sibling content.md")
    return

  from skill_bill.constants import (
    FEATURE_VERIFY_WORKFLOW_ARTIFACT_NAMES,
    FEATURE_VERIFY_WORKFLOW_STEP_IDS,
  )

  content_text = content_file.read_text(encoding="utf-8")
  required_markers = (
    "audit-rubrics.md",
    "feature_verify_started",
    "feature_verify_finished",
    "feature_verify_workflow_open",
    "feature_verify_workflow_update",
    "feature_verify_workflow_get",
    "feature_verify_workflow_continue",
  )
  for marker in required_markers:
    if marker not in content_text:
      issues.append(
        f"{content_file}: bill-feature-verify content must include '{marker}'"
      )

  for step_id in FEATURE_VERIFY_WORKFLOW_STEP_IDS:
    if step_id == "finish":
      continue
    binding = f"Step id: `{step_id}`"
    if binding not in content_text:
      issues.append(
        f"{content_file}: bill-feature-verify content must bind step '{step_id}' "
        f"with the inline marker '{binding}'"
      )

  for artifact_name in FEATURE_VERIFY_WORKFLOW_ARTIFACT_NAMES:
    if f"`{artifact_name}`" not in content_text:
      issues.append(
        f"{content_file}: bill-feature-verify content must reference artifact '`{artifact_name}`'"
      )


def validate_no_inline_telemetry_contract_drift(root: Path, issues: list[str]) -> None:
  """Reject telemeterable skills that contain inline telemetry contract markers.

  Every telemeterable skill should reference the shared telemetry-contract.md
  sidecar instead of embedding shared contract text. This check catches drift
  re-accumulation: a skill that references the sidecar but also contains
  forbidden marker text fails validation.
  """
  files_to_scan = sorted((root / "skills").rglob("SKILL.md"))
  platform_pack_files = sorted((root / "platform-packs").rglob("SKILL.md"))
  files_to_scan.extend(platform_pack_files)

  for skill_file in files_to_scan:
    skill_dir = skill_file.parent
    skill_name = skill_dir.name
    if "telemetry-contract.md" not in required_supporting_files_for_skill(skill_name):
      continue

    scan_paths = [skill_file]
    orchestrator_files = dict(ORCHESTRATOR_SKILLS)
    if str(skill_dir.relative_to(root)) in orchestrator_files:
      for f in orchestrator_files[str(skill_dir.relative_to(root))]:
        if f not in scan_paths:
          scan_paths.append(skill_dir / f)

    for file_path in scan_paths:
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

  try:
    validate_skill_md_shape(skill_file)
  except InvalidSkillMdShapeError as error:
    issues.append(str(error))

  validate_runtime_supporting_files(skill_name, text, skill_file, issues)
  validate_portable_review_wording(skill_name, text, skill_file, issues)


def validate_runtime_supporting_files(
  skill_name: str,
  text: str,
  skill_file: Path,
  issues: list[str],
) -> None:
  required_files = required_supporting_files_for_skill(skill_name)
  if not required_files:
    return

  content_text = ""
  content_file = skill_file.parent / "content.md"
  if content_file.is_file():
    content_text = content_file.read_text(encoding="utf-8")
  combined_text = text

  for pattern, message in EXTERNAL_PLAYBOOK_REFERENCE_PATTERNS:
    match = pattern.search(combined_text)
    if match:
      issues.append(f"{skill_file}: {message}; found '{match.group(0)}'")

  full_combined_text = text if not content_text else f"{text}\n{content_text}"
  for file_name in required_files:
    supporting_file = skill_file.parent / file_name
    if (
      skill_name == "bill-feature-implement"
      and file_name in ADDON_SUPPORTING_FILE_TARGETS
    ):
      if (
        file_name not in full_combined_text
        and "matching pack-owned add-on supporting files" not in full_combined_text
      ):
        issues.append(
          f"{skill_file}: must reference local supporting file '{file_name}' or describe pack-owned add-on support-file selection"
        )
    elif file_name in ADDON_SUPPORTING_FILE_TARGETS:
      if file_name not in full_combined_text:
        issues.append(f"{skill_file}: must reference local supporting file '{file_name}'")
    elif file_name not in full_combined_text:
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
  content_text = ""
  content_file = skill_file.parent / "content.md"
  if content_file.is_file():
    content_text = content_file.read_text(encoding="utf-8")
  combined_text = text if not content_text else f"{text}\n{content_text}"

  if skill_name == "bill-code-review" and REVIEW_SESSION_ID_PLACEHOLDER not in combined_text:
    issues.append(f"{skill_file}: shared code-review router must expose '{REVIEW_SESSION_ID_PLACEHOLDER}'")
  if skill_name == "bill-code-review" and REVIEW_SESSION_ID_FORMAT not in combined_text:
    issues.append(
      f"{skill_file}: shared code-review router must define the review session id format '{REVIEW_SESSION_ID_FORMAT}'"
    )
  if skill_name == "bill-code-review" and REVIEW_RUN_ID_PLACEHOLDER not in combined_text:
    issues.append(f"{skill_file}: shared code-review router must expose '{REVIEW_RUN_ID_PLACEHOLDER}'")
  if skill_name == "bill-code-review" and REVIEW_RUN_ID_FORMAT not in combined_text:
    issues.append(f"{skill_file}: shared code-review router must define the review run id format '{REVIEW_RUN_ID_FORMAT}'")
  if skill_name == "bill-code-review" and APPLIED_LEARNINGS_PLACEHOLDER not in combined_text:
    issues.append(f"{skill_file}: shared code-review router must expose '{APPLIED_LEARNINGS_PLACEHOLDER}'")

  if skill_name not in PORTABLE_REVIEW_SKILLS:
    return

  if REVIEW_SESSION_ID_PLACEHOLDER not in combined_text:
    issues.append(f"{skill_file}: portable review skills must expose '{REVIEW_SESSION_ID_PLACEHOLDER}'")
  if REVIEW_RUN_ID_PLACEHOLDER not in combined_text:
    issues.append(f"{skill_file}: portable review skills must expose '{REVIEW_RUN_ID_PLACEHOLDER}'")
  if APPLIED_LEARNINGS_PLACEHOLDER not in combined_text:
    issues.append(f"{skill_file}: portable review skills must expose '{APPLIED_LEARNINGS_PLACEHOLDER}'")

  for pattern, message in NON_PORTABLE_REVIEW_PATTERNS:
    match = pattern.search(combined_text)
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
  allowed_packages = _discover_allowed_packages(root)
  container_name = ""
  relative_path: Path
  if addon_file.is_relative_to(root / "platform-packs"):
    container_name = "platform-packs"
    relative_path = addon_file.relative_to(root / "platform-packs")
  elif addon_file.is_relative_to(root / "skills"):
    container_name = "skills"
    relative_path = addon_file.relative_to(root / "skills")
  else:
    issues.append(f"{addon_file}: governed add-on is outside skills/ and platform-packs/")
    return

  parts = relative_path.parts
  if len(parts) != 3:
    issues.append(
      f"{addon_file}: expected add-on path format platform-packs/<package>/{ADDON_DIRECTORY_NAME}/<addon-file>.md, "
      f"got {container_name}/{relative_path}"
    )
    return

  package_name, directory_name, file_name = parts
  if directory_name != ADDON_DIRECTORY_NAME:
    issues.append(
      f"{addon_file}: governed add-ons must live under platform-packs/<package>/{ADDON_DIRECTORY_NAME}/"
    )
    return

  if container_name == "skills":
    if package_name == "base":
      issues.append(f"{addon_file}: governed add-ons must be pack-owned, not placed under skills/base/")
    else:
      issues.append(
        f"{addon_file}: governed add-ons must live under platform-packs/<package>/{ADDON_DIRECTORY_NAME}/, "
        f"not skills/{package_name}/{ADDON_DIRECTORY_NAME}/"
      )
    return

  if package_name not in allowed_packages:
    issues.append(
      f"{addon_file}: add-on package '{package_name}' is not allowed; use one of "
      f"{', '.join(package for package in allowed_packages if package != 'base')}"
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
  skills_dir = next((parent for parent in skill_file.parents if parent.name == "skills"), None)
  if skills_dir is None:
    issues.append(f"{skill_file}: could not resolve owning skills/ directory")
    return
  allowed_packages = _discover_allowed_packages(skills_dir.parent)
  relative_path = skill_file.relative_to(skills_dir)
  parts = relative_path.parts

  package_name = ""
  directory_name = ""
  file_name = ""
  if len(parts) == 2:
    directory_name, file_name = parts
    package_name = "base"
  elif len(parts) == 3:
    package_name, directory_name, file_name = parts
    if package_name == "base":
      issues.append(
        f"{skill_file}: canonical skills must live directly under skills/<skill>/SKILL.md, not skills/base/{directory_name}/SKILL.md"
      )
      return
  else:
    issues.append(
      f"{skill_file}: expected path format skills/<skill>/SKILL.md or skills/<package>/<skill>/SKILL.md, got skills/{relative_path}"
    )
    return

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

  if package_name not in allowed_packages:
    issues.append(
      f"{skill_file}: package '{package_name}' is not allowed; use one of {', '.join(allowed_packages)}"
    )
    return

  expected_prefixes = expected_prefixes_for_package(package_name)
  if package_name == "base":
    platform_prefixes = tuple(
      f"bill-{discovered_package}-"
      for discovered_package in allowed_packages
      if discovered_package != "base"
    )
    if any(skill_name.startswith(prefix) for prefix in platform_prefixes):
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
  capabilities: set[str] = set()
  for skill_dir in skills_dir.iterdir():
    if skill_dir.is_dir() and skill_dir.name.startswith("bill-"):
      capabilities.add(skill_dir.name.removeprefix("bill-"))

  legacy_base_dir = skills_dir / "base"
  if legacy_base_dir.is_dir():
    for skill_dir in legacy_base_dir.iterdir():
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


def validate_readme(
  readme_path: Path,
  skill_files: dict[str, Path],
  platform_pack_skill_names: list[str],
  issues: list[str],
) -> None:
  text = readme_path.read_text(encoding="utf-8")

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
  actual_set = sorted(set(skill_files) | set(platform_pack_skill_names))
  platform_pack_set = set(platform_pack_skill_names)
  catalog_required_skill_set = sorted(
    skill_name
    for skill_name, skill_file in skill_files.items()
    if len(skill_file.relative_to(readme_path.parent).parts) == 3
    and skill_name not in platform_pack_set
  )

  missing_from_readme = sorted(set(catalog_required_skill_set) - set(documented_set))
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
