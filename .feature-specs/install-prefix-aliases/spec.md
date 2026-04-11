# Feature: install-prefix-aliases
Created: 2026-04-02
Status: In Progress
Sources: chat discussion for SKILL-2 about install-time user-facing skill prefixes

## Acceptance Criteria
1. The installer prompts for a user-facing skill prefix.
2. Pressing Enter keeps the default prefix `bill`.
3. A custom prefix changes the installed command names without changing the repo's canonical `bill-*` taxonomy.
4. Installed aliased skills keep working for routing and cross-skill references.
5. Reinstalling or uninstalling removes previously installed aliases cleanly.
6. Installer docs and tests are updated.

## Non-Goals
- Changing canonical in-repo skill names
- Redesigning the taxonomy
- Changing review behavior unrelated to install-time naming

## Open Questions
None

---

## Consolidated Spec

Add install-time support for a user-facing skill prefix so teams can invoke Skill Bill commands under their own namespace while leaving the repository's canonical skill names unchanged.

The default behavior should remain the current `bill-*` namespace. If the user presses Enter at the new prefix prompt, installation should proceed with the canonical `bill` prefix and preserve existing behavior.

If the user provides a custom prefix, the installed command names should use that prefix instead of `bill`, but the repository itself must continue using canonical `bill-*` names. This should be treated as an install-time alias layer only, not a taxonomy rewrite.

Aliased installs must continue to work for routed and delegated workflows. That means cross-skill references inside installed skill files and supporting markdown contracts need to resolve under the chosen prefix rather than pointing at canonical command names that are not installed under that namespace.

Reinstall and uninstall behavior must remain clean. Existing installs under a previous prefix should be removed safely before reinstalling with a new prefix, and uninstall should clean up generated alias installs as well as canonical symlink installs.

The installer and related documentation should explain the prefix behavior clearly enough that teams understand the difference between canonical repository names and installed command aliases.
