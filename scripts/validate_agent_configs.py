#!/usr/bin/env python3

from __future__ import annotations

from pathlib import Path
import json
import re
import sys

from skill_repo_contracts import (
  APPLIED_LEARNINGS_PLACEHOLDER,
  ORCHESTRATION_PLAYBOOKS,
  PORTABLE_REVIEW_SKILLS,
  REVIEW_DELEGATION_REQUIRED_SECTIONS,
  REVIEW_RUN_ID_FORMAT,
  REVIEW_RUN_ID_PLACEHOLDER,
  REVIEW_SESSION_ID_FORMAT,
  REVIEW_SESSION_ID_PLACEHOLDER,
  RISK_REGISTER_FINDING_FORMAT,
  RUNTIME_SUPPORTING_FILES,
)


README_TOTAL_PATTERN = re.compile(r"collection of (\d+) AI skills")
README_SECTION_PATTERN = re.compile(r"^### (.+) \((\d+) skills\)$")
README_SKILL_ROW_PATTERN = re.compile(r"^\| `/(bill-[a-z0-9-]+)` \|")
SKILL_REFERENCE_PATTERN = re.compile(r"(?<![A-Za-z0-9.-])(bill-[a-z0-9-]+)(?![A-Za-z0-9-])")
FRONTMATTER_PATTERN = re.compile(r"\A---\n(.*?)\n---\n", re.DOTALL)
SKILL_NAME_PATTERN = re.compile(r"^bill-[a-z0-9-]+$")
PROJECT_OVERRIDES_HEADING = "## Project Overrides"
SKILL_OVERRIDE_FILE = ".agents/skill-overrides.md"
SKILL_OVERRIDE_EXAMPLE_FILE = ".agents/skill-overrides.example.md"
SKILL_OVERRIDE_TITLE = "# Skill Overrides"
SKILL_OVERRIDE_SECTION_PATTERN = re.compile(r"^## (bill-[a-z0-9-]+)$")
ALLOWED_PACKAGES = ("base", "agent-config", "kotlin", "kmp", "backend-kotlin", "php", "go")
APPROVED_CODE_REVIEW_AREAS = {
  "api-contracts",
  "architecture",
  "performance",
  "persistence",
  "platform-correctness",
  "reliability",
  "security",
  "testing",
  "ui",
  "ux-accessibility",
}
EXTERNAL_PLAYBOOK_REFERENCE_PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = (
  (
    re.compile(r"\.bill-shared/orchestration/"),
    "must reference skill-local supporting files instead of install-local playbook paths",
  ),
  (
    re.compile(r"orchestration/(?:stack-routing|review-orchestrator|review-delegation)/PLAYBOOK\.md"),
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


def main() -> int:
  root = resolve_root()
  issues: list[str] = []

  skill_files = discover_skill_files(root, issues)
  skill_names = sorted(skill_files.keys())

  for skill_name, skill_file in skill_files.items():
    validate_skill_file(skill_name, skill_file, issues)

  validate_readme(root / "README.md", skill_names, issues)
  validate_orchestration_playbooks(root, issues)
  validate_skill_references(root, skill_names, issues)
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

  if issues:
    print("Agent-config validation failed:")
    for issue in issues:
      print(f"- {issue}")
    return 1

  print("Agent-config validation passed.")
  print(f"Validated {len(skill_names)} skills, README catalog, skill references, and plugin metadata.")
  return 0


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
    if file_name not in text:
      issues.append(f"{skill_file}: must reference local supporting file '{file_name}'")
    if not supporting_file.exists():
      issues.append(f"{skill_file}: supporting file '{file_name}' is missing")
    elif not supporting_file.is_symlink():
      issues.append(f"{skill_file}: supporting file '{file_name}' must be a symlink to the shared snapshot")


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
