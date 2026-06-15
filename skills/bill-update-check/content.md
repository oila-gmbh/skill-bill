---
name: bill-update-check
description: Check whether the installed Skill Bill runtime is behind the latest GitHub release.
---

# Update Check Content

Run the runtime command:

```bash
skill-bill update-check
```

Use JSON output when the caller needs machine-readable output:

```bash
skill-bill update-check --format json
```

To compare against prerelease tags as well as stable releases, pass:

```bash
skill-bill update-check --include-prereleases
```

Do not inspect GitHub releases directly in this skill content, run `install.sh`,
rewrite installed skill links, or mutate workflow state. The runtime command owns
release selection, version comparison, output formatting, and soft failure
handling.
