"""Agent-detection and skill-install primitives (SKILL-15).

Shared install logic so both ``install.sh`` and the new-skill scaffolder can
symlink skills into detected local AI agents without duplicating path rules.

The agent paths here are the canonical source of truth — ``install.sh`` shells
out to this module via ``python3 -m skill_bill install ...`` rather than
redefining them inline. Keeping one owner for the path table prevents drift.

Supported agents (mirrors ``install.sh::get_agent_path``):

- ``copilot``  -> ``~/.copilot/skills``
- ``claude``   -> ``~/.claude/commands``
- ``opencode`` -> ``~/.config/opencode/skills``;
   ``~/.config/opencode/agents`` for OpenCode markdown subagents
- ``codex``    -> ``~/.codex/skills`` with ``~/.agents/skills`` fallback (skills);
   ``~/.codex/agents`` with ``~/.agents/agents`` fallback (TOML subagents)
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable


SUPPORTED_AGENTS: tuple[str, ...] = (
  "copilot",
  "claude",
  "codex",
  "opencode",
)


CODEX_AGENTS_KIND: str = "codex-agents"
OPENCODE_AGENTS_KIND: str = "opencode-agents"


@dataclass(frozen=True)
class AgentTarget:
  """A detected agent's canonical install directory.

  Attributes:
    name: canonical agent name (``claude``, ``copilot``, ...).
    path: directory that holds skill symlinks for that agent.
  """

  name: str
  path: Path


@dataclass
class InstallTransaction:
  """Rollback bookkeeping for :func:`install_skill`.

  The scaffolder threads an :class:`InstallTransaction` through
  :func:`install_skill` so a validator failure downstream can cleanly
  reverse every symlink the installer created. Standalone callers (like
  ``install.sh``) may omit the transaction; they only use the module for
  agent detection.
  """

  created_symlinks: list[Path] = field(default_factory=list)


def _codex_path(home: Path) -> Path:
  """Mirror ``install.sh::get_agent_path`` for codex.

  Prefers ``~/.codex/skills`` when ``~/.codex`` or ``~/.codex/skills``
  exists; otherwise falls back to ``~/.agents/skills``. The fallback keeps
  the module honest for older codex installations that still use the
  ``~/.agents`` layout.
  """
  codex_root = home / ".codex"
  codex_skills = codex_root / "skills"
  if codex_root.exists() or codex_skills.exists():
    return codex_skills
  return home / ".agents" / "skills"


def _codex_agents_path(home: Path) -> Path:
  """Resolve the Codex native subagents TOML directory.

  Prefers ``~/.codex/agents`` when ``~/.codex`` or ``~/.codex/agents``
  exists; otherwise falls back to ``~/.agents/agents``. Mirrors the skills
  fallback model so older codex layouts keep working without duplicate
  path rules.
  """
  codex_root = home / ".codex"
  codex_agents = codex_root / "agents"
  if codex_root.exists() or codex_agents.exists():
    return codex_agents
  return home / ".agents" / "agents"


def _opencode_agents_path(home: Path) -> Path:
  """Resolve the OpenCode native markdown subagents directory."""
  return home / ".config" / "opencode" / "agents"


def agent_paths(home: Path | None = None) -> dict[str, Path]:
  """Return the canonical agent -> install-directory mapping.

  Args:
    home: optional override for ``$HOME``. Tests monkeypatch this via the
      ``HOME`` env var or by calling with a fixture directory.

  Returns:
    Mapping from agent name to absolute install path.
  """
  resolved_home = home if home is not None else Path.home()
  return {
    "copilot": resolved_home / ".copilot" / "skills",
    "claude": resolved_home / ".claude" / "commands",
    "opencode": resolved_home / ".config" / "opencode" / "skills",
    "codex": _codex_path(resolved_home),
  }


def codex_agents_path(home: Path | None = None) -> Path:
  """Public accessor for the resolved Codex agents TOML directory."""
  resolved_home = home if home is not None else Path.home()
  return _codex_agents_path(resolved_home)


def opencode_agents_path(home: Path | None = None) -> Path:
  """Public accessor for the resolved OpenCode agents markdown directory."""
  resolved_home = home if home is not None else Path.home()
  return _opencode_agents_path(resolved_home)


def detect_agents(home: Path | None = None) -> list[AgentTarget]:
  """Return the list of agents whose parent directories already exist.

  An agent is considered "detected" when any ancestor on its canonical
  install path already exists on disk. This mirrors ``install.sh``'s
  existing UX: we don't create agent homes as a side effect of scaffolding
  a skill — operators run ``./install.sh`` for that. We just install into
  agents that the operator has already set up.
  """
  resolved_home = home if home is not None else Path.home()
  detected: list[AgentTarget] = []
  for agent in SUPPORTED_AGENTS:
    path = agent_paths(resolved_home)[agent]
    if _agent_is_present(resolved_home, agent, path):
      detected.append(AgentTarget(name=agent, path=path))
  return detected


def detect_codex_agents_target(home: Path | None = None) -> AgentTarget | None:
  """Return the Codex agents-directory target when Codex is detected.

  Codex-specific: this is the secondary install surface alongside the
  skills directory. Returns ``None`` when no Codex install is detected so
  callers can short-circuit cleanly.
  """
  resolved_home = home if home is not None else Path.home()
  agents_dir = _codex_agents_path(resolved_home)
  if _agent_is_present(resolved_home, "codex", agents_dir):
    return AgentTarget(name=CODEX_AGENTS_KIND, path=agents_dir)
  return None


def detect_opencode_agents_target(home: Path | None = None) -> AgentTarget | None:
  """Return the OpenCode agents-directory target when OpenCode is detected."""
  resolved_home = home if home is not None else Path.home()
  agents_dir = _opencode_agents_path(resolved_home)
  if _agent_is_present(resolved_home, "opencode", agents_dir):
    return AgentTarget(name=OPENCODE_AGENTS_KIND, path=agents_dir)
  return None


def _agent_is_present(home: Path, agent: str, install_path: Path) -> bool:
  """Return True when the agent's root directory exists on disk.

  We look at the agent's top-level root (e.g. ``~/.claude``, ``~/.copilot``)
  rather than the exact install subdirectory so the installer can create
  the skills/commands subdir on first run without requiring the operator
  to pre-create it.
  """
  if install_path.exists():
    return True
  roots_by_agent: dict[str, tuple[Path, ...]] = {
    "copilot": (home / ".copilot",),
    "claude": (home / ".claude",),
    "opencode": (home / ".config" / "opencode",),
    "codex": (home / ".codex", home / ".agents"),
  }
  for root in roots_by_agent.get(agent, ()):
    if root.exists():
      return True
  return False


def install_skill(
  skill_path: Path,
  agent_targets: Iterable[AgentTarget],
  *,
  transaction: InstallTransaction | None = None,
) -> list[Path]:
  """Symlink ``skill_path`` into each detected agent directory.

  ``skill_path`` MUST be a directory containing ``SKILL.md``. Standalone
  markdown files (for example add-on ``*.md`` assets under
  ``platform-packs/<platform>/addons/``) are NOT installed via this function — they
  ship with their owning platform package as supporting assets and are
  resolved through sibling-file lookup at runtime rather than by symlinking
  each file into every agent's install directory. The scaffolder's
  ``_perform_install`` short-circuits the add-on kind for exactly this
  reason; passing a file here raises :class:`FileNotFoundError`.

  Args:
    skill_path: absolute path to the skill directory (must contain
      ``SKILL.md``).
    agent_targets: iterable of detected agents (as returned by
      :func:`detect_agents`).
    transaction: optional rollback recorder. Every symlink created is
      appended so the caller can reverse them on failure.

  Returns:
    List of absolute symlink paths that were created. Pre-existing symlinks
    pointing at the same source are skipped and not returned.
  """
  skill_path = Path(skill_path).resolve()
  if not skill_path.is_dir():
    raise FileNotFoundError(f"Skill directory '{skill_path}' does not exist.")

  created: list[Path] = []
  for target in agent_targets:
    target.path.mkdir(parents=True, exist_ok=True)
    link_path = target.path / skill_path.name
    if link_path.is_symlink() and link_path.resolve(strict=False) == skill_path:
      continue
    if link_path.is_symlink() or link_path.exists():
      link_path.unlink()
    link_path.symlink_to(skill_path)
    created.append(link_path)
    if transaction is not None:
      transaction.created_symlinks.append(link_path)
  return created


def uninstall_skill(skill_path: Path, agent_targets: Iterable[AgentTarget]) -> list[Path]:
  """Remove symlinks to ``skill_path`` from each detected agent directory.

  Returns the list of paths that were actually removed. Paths that do not
  exist or point elsewhere are silently skipped — uninstall is idempotent.
  """
  skill_path = Path(skill_path).resolve()
  removed: list[Path] = []
  for target in agent_targets:
    link_path = target.path / skill_path.name
    if not link_path.is_symlink():
      continue
    if link_path.resolve(strict=False) != skill_path:
      continue
    link_path.unlink()
    removed.append(link_path)
  return removed


def discover_codex_agent_tomls(
  platform_packs_root: Path,
  skills_root: Path | None = None,
) -> list[Path]:
  """Return every ``codex-agents/*.toml`` under both discovery roots.

  Manifest-driven: walks ``platform-packs/<slug>/**/codex-agents/*.toml``
  and, when ``skills_root`` is provided, ``skills/<slug>/**/codex-agents/*.toml``
  so subagent definitions co-located with author-owned skills (such as
  ``skills/bill-feature-implement/codex-agents/``) are picked up alongside
  the platform packs. Results are deduplicated by resolved path and sorted
  for stable ordering.
  """
  results: dict[Path, None] = {}
  for root_candidate in (platform_packs_root, skills_root):
    if root_candidate is None:
      continue
    root = Path(root_candidate)
    if not root.is_dir():
      continue
    for toml_file in root.rglob("codex-agents/*.toml"):
      if toml_file.is_file():
        results[toml_file.resolve()] = None
  return sorted(results)


def discover_opencode_agent_mds(
  platform_packs_root: Path,
  skills_root: Path | None = None,
) -> list[Path]:
  """Return every ``opencode-agents/*.md`` under both discovery roots.

  Manifest-driven: walks ``platform-packs/<slug>/**/opencode-agents/*.md``
  and, when ``skills_root`` is provided, ``skills/<slug>/**/opencode-agents/*.md``
  so subagent definitions co-located with author-owned skills (such as
  ``skills/bill-feature-implement/opencode-agents/``) are picked up alongside
  the platform packs. Results are deduplicated by resolved path and sorted
  for stable ordering.
  """
  results: dict[Path, None] = {}
  for root_candidate in (platform_packs_root, skills_root):
    if root_candidate is None:
      continue
    root = Path(root_candidate)
    if not root.is_dir():
      continue
    for md_file in root.rglob("opencode-agents/*.md"):
      if md_file.is_file():
        results[md_file.resolve()] = None
  return sorted(results)


def install_codex_agent_toml(
  toml_path: Path,
  agent_target: AgentTarget,
  *,
  transaction: InstallTransaction | None = None,
) -> Path | None:
  """Symlink a single TOML subagent file into the Codex agents directory.

  Returns the created symlink path, or ``None`` when the existing symlink
  already points at the same source.
  """
  toml_path = Path(toml_path).resolve()
  if not toml_path.is_file():
    raise FileNotFoundError(f"TOML file '{toml_path}' does not exist.")
  agent_target.path.mkdir(parents=True, exist_ok=True)
  link_path = agent_target.path / toml_path.name
  if link_path.is_symlink() and link_path.resolve(strict=False) == toml_path:
    return None
  if link_path.is_symlink() or link_path.exists():
    link_path.unlink()
  link_path.symlink_to(toml_path)
  if transaction is not None:
    transaction.created_symlinks.append(link_path)
  return link_path


def install_opencode_agent_md(
  md_path: Path,
  agent_target: AgentTarget,
  *,
  transaction: InstallTransaction | None = None,
) -> Path | None:
  """Symlink a single markdown subagent file into the OpenCode agents directory.

  Returns the created symlink path, or ``None`` when the existing symlink
  already points at the same source.
  """
  md_path = Path(md_path).resolve()
  if not md_path.is_file():
    raise FileNotFoundError(f"Markdown file '{md_path}' does not exist.")
  agent_target.path.mkdir(parents=True, exist_ok=True)
  link_path = agent_target.path / md_path.name
  if link_path.is_symlink() and link_path.resolve(strict=False) == md_path:
    return None
  if link_path.is_symlink() or link_path.exists():
    link_path.unlink()
  link_path.symlink_to(md_path)
  if transaction is not None:
    transaction.created_symlinks.append(link_path)
  return link_path


def uninstall_codex_agent_tomls(
  platform_packs_root: Path,
  home: Path | None = None,
  skills_root: Path | None = None,
) -> list[Path]:
  """Remove every TOML symlink under both candidate Codex agents dirs.

  Iterates through the manifest-driven list of authored TOML filenames and
  removes any matching symlink in ``~/.codex/agents`` and
  ``~/.agents/agents``. Idempotent: missing or non-symlink entries are
  silently skipped.
  """
  resolved_home = home if home is not None else Path.home()
  toml_files = discover_codex_agent_tomls(platform_packs_root, skills_root)
  if not toml_files:
    return []
  candidate_dirs = (
    resolved_home / ".codex" / "agents",
    resolved_home / ".agents" / "agents",
  )
  removed: list[Path] = []
  for toml_path in toml_files:
    for candidate_dir in candidate_dirs:
      link_path = candidate_dir / toml_path.name
      if not link_path.is_symlink():
        continue
      try:
        if link_path.resolve(strict=False) != toml_path:
          continue
      except OSError:
        continue
      link_path.unlink()
      removed.append(link_path)
  return removed


def uninstall_opencode_agent_mds(
  platform_packs_root: Path,
  home: Path | None = None,
  skills_root: Path | None = None,
) -> list[Path]:
  """Remove every OpenCode markdown-agent symlink from the agents dir.

  Iterates through the manifest-driven list of authored markdown filenames and
  removes any matching symlink in ``~/.config/opencode/agents``. Idempotent:
  missing or non-symlink entries are silently skipped.
  """
  resolved_home = home if home is not None else Path.home()
  md_files = discover_opencode_agent_mds(platform_packs_root, skills_root)
  if not md_files:
    return []
  agents_dir = _opencode_agents_path(resolved_home)
  removed: list[Path] = []
  for md_path in md_files:
    link_path = agents_dir / md_path.name
    if not link_path.is_symlink():
      continue
    try:
      if link_path.resolve(strict=False) != md_path:
        continue
    except OSError:
      continue
    link_path.unlink()
    removed.append(link_path)
  return removed


def uninstall_targets(created_symlinks: Iterable[Path]) -> list[Path]:
  """Remove the exact symlinks recorded in an :class:`InstallTransaction`.

  Used by scaffold rollback. We remove by exact path rather than by agent
  so the rollback can never accidentally unlink a pre-existing symlink that
  the installer didn't create.
  """
  removed: list[Path] = []
  for link_path in created_symlinks:
    path = Path(link_path)
    if path.is_symlink():
      path.unlink()
      removed.append(path)
  return removed


__all__ = [
  "AgentTarget",
  "CODEX_AGENTS_KIND",
  "InstallTransaction",
  "OPENCODE_AGENTS_KIND",
  "SUPPORTED_AGENTS",
  "_opencode_agents_path",
  "agent_paths",
  "codex_agents_path",
  "detect_agents",
  "detect_codex_agents_target",
  "detect_opencode_agents_target",
  "discover_codex_agent_tomls",
  "discover_opencode_agent_mds",
  "install_codex_agent_toml",
  "install_opencode_agent_md",
  "install_skill",
  "opencode_agents_path",
  "uninstall_codex_agent_tomls",
  "uninstall_opencode_agent_mds",
  "uninstall_skill",
  "uninstall_targets",
]
