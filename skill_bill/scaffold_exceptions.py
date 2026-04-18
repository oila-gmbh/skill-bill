"""Named exceptions for the new-skill scaffolder (SKILL-15).

Mirrors the naming pattern used by
``skill_bill/shell_content_contract.py`` — a single base class plus a concrete
subclass per failure mode. Every subclass carries the offending artifact in
its message so the CLI prints an actionable error instead of a traceback.

The scaffolder never silently falls back: every branch that cannot proceed
raises a specific named exception. Callers catch :class:`ScaffoldError` to
surface any scaffolder failure uniformly, but the orchestrator and tests
match on the concrete subclass so they can assert the exact failure mode.
"""

from __future__ import annotations


class ScaffoldError(Exception):
  """Base class for all new-skill scaffolder failures.

  Callers (the CLI, the MCP tool, tests, rollback machinery) catch this
  base type to surface any scaffolder failure uniformly, but each concrete
  subclass names the specific failure mode so operators know exactly which
  artifact to fix.
  """


class ScaffoldPayloadVersionMismatchError(ScaffoldError):
  """Raised when ``scaffold_payload_version`` does not match the scaffolder.

  The scaffolder pins its target payload version via
  :data:`skill_bill.constants.SCAFFOLD_PAYLOAD_VERSION`. Payloads produced
  against a different version must bump the scaffolder and every caller in
  lockstep, so the mismatch is surfaced instead of being silently coerced.
  """


class InvalidScaffoldPayloadError(ScaffoldError):
  """Raised when a payload fails schema validation.

  The message names the missing or malformed field so callers know exactly
  which key to fix. A payload that is syntactically valid JSON but missing
  required fields still raises this error.
  """


class SkillAlreadyExistsError(ScaffoldError):
  """Raised when the target skill path already exists on disk.

  The scaffolder is intentionally non-destructive: second runs against an
  existing skill path fail loudly with this error so callers explicitly
  remove or rename the existing skill before retrying.
  """


class ScaffoldValidatorError(ScaffoldError):
  """Raised when ``scripts/validate_agent_configs.py`` fails post-scaffold.

  The validator's stderr is attached verbatim so operators see the exact
  contract violation. The scaffolder rolls back all staged changes before
  raising this error; the repo tree is byte-identical to the pre-run state.
  """


class ScaffoldRollbackError(ScaffoldError):
  """Raised when rollback itself fails after a previous failure.

  This is the only failure mode that may leave the repo in a partially
  inconsistent state. The message names the original failure and the
  rollback step that could not complete so operators can finish the rollback
  by hand.
  """


class UnknownSkillKindError(ScaffoldError):
  """Raised when ``kind`` is not one of the supported skill kinds.

  Supported kinds: ``horizontal``, ``platform-override-piloted``,
  ``platform-pack``, ``code-review-area``, ``add-on``.
  """


class UnknownPreShellFamilyError(ScaffoldError):
  """Raised when a pre-shell family slug is not in the registered set.

  The scaffolder only accepts pre-shell families declared in
  :data:`skill_bill.constants.PRE_SHELL_FAMILIES`. New families must be added
  to both that tuple and the family registry in
  :mod:`skill_bill.scaffold` in the same change.
  """


class MissingSupportingFileTargetError(ScaffoldError):
  """Raised when a declared runtime-supporting-file has no registered target.

  The scaffolder wires sibling supporting-file symlinks using the mapping in
  :data:`scripts.skill_repo_contracts.SUPPORTING_FILE_TARGETS`. When a skill
  lists a file name in :data:`scripts.skill_repo_contracts.RUNTIME_SUPPORTING_FILES`
  that is not registered there, we refuse to silently skip the symlink —
  callers must either register the target or drop the reference. This keeps
  the "never silently fall back" rule authoritative.
  """


class MissingPlatformPackError(ScaffoldError):
  """Raised when a platform pack referenced by the payload does not exist.

  Platform-override-piloted and code-review-area kinds require an existing
  pack under ``platform-packs/<slug>/``. The new platform-pack kind creates
  the pack root explicitly; callers use that flow when they want the
  scaffolder to generate the governing manifest and default content files.
  """


__all__ = [
  "InvalidScaffoldPayloadError",
  "MissingPlatformPackError",
  "MissingSupportingFileTargetError",
  "ScaffoldError",
  "ScaffoldPayloadVersionMismatchError",
  "ScaffoldRollbackError",
  "ScaffoldValidatorError",
  "SkillAlreadyExistsError",
  "UnknownPreShellFamilyError",
  "UnknownSkillKindError",
]
