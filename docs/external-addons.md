# External Addon Sources

External addon sources let you overlay private or team-specific review add-ons onto an
installed platform pack **without forking the pack or committing the add-on into this repo**.
The add-on content lives in a directory you own; Skill Bill merges it into the installed pack
during every install.

Use [Getting Started](getting-started.md) for install and full CLI coverage, and
[Getting Started for Teams](getting-started-for-teams.md) for the broader customization model.
External addons are the lightest way to ship an add-on that must stay outside the shared repo.

## When To Use This

Reach for an external addon source when all of these hold:

- The add-on extends an **existing installed pack** (e.g. `ios`, `kotlin`, `php`), not a new stack.
- The content is **private or team-specific** and should not live in the public repo.
- You want it applied automatically on install, the same way pack-owned add-ons are.

If the behavior is durable and shareable, prefer a pack-owned add-on or a forked pack instead
(see [Customization Layers](getting-started-for-teams.md#customization-layers)). External sources
are for content that deliberately stays out of tree.

## How It Works

The overlay runs automatically during `./install.sh`, **after** the installed platform packs are
reconciled from upstream and **before** skills are staged. For each configured source it:

1. Reads the machine-global config and resolves each `{ path, platform }` entry.
2. Skips (with a warning) any source whose target platform pack is not installed.
3. Validates the source's `addon-manifest.yaml` and referenced `.md` files.
4. Copies the add-on `.md` files into the installed pack's `addons/` directory.
5. Appends the add-on's `addon_usage` and `pointers` entries into the installed pack's
   `platform.yaml`.

Because the overlay always re-applies against the freshly reconciled pack, it is idempotent:
re-running install never duplicates entries, and a wiped `addons/` directory is reconstituted on
the next install.

## Configuring Sources

Sources are declared in the machine-global config file:

- Default path: `~/.skill-bill/config.json`
- Override with the `SKILL_BILL_CONFIG_PATH` environment variable (supports `~` expansion)

Add an `external_addon_sources` array. Each entry is a `{ path, platform }` object:

```json
{
  "external_addon_sources": [
    { "path": "~/dev/acme-review-addon", "platform": "ios" }
  ]
}
```

| Field | Meaning |
|-------|---------|
| `path` | Directory containing `addon-manifest.yaml` and the referenced `.md` files. Supports `~`; relative paths resolve against the current working directory. Must exist and be a directory. |
| `platform` | The platform-pack slug to overlay onto (e.g. `ios`). If that pack is not installed, the source is skipped with a warning, not treated as an error. |

`external_addon_sources` shares `config.json` with telemetry settings; other keys in the file are
left untouched.

## Persisting Config Across Installs

**A default `./install.sh` (and every update, which is just a re-run) wipes `~/.skill-bill/` as a
pre-install cleanup step.** It preserves `skills/`, `platform-packs/`, `orchestration/`,
`baseline-manifest.json`, and durable `*.db` state — but **not `config.json`**. A config left at the
default `~/.skill-bill/config.json` path therefore loses its `external_addon_sources` on the next
install.

To keep your sources across installs, **relocate the config outside `~/.skill-bill/` and point
`SKILL_BILL_CONFIG_PATH` at it.** Both the installer and the runtime read this variable, so a clean
install never touches the file. Export it from your shell profile so every `install.sh` and
`skill-bill` invocation sees it:

```bash
# ~/.bashrc / ~/.zshrc
export SKILL_BILL_CONFIG_PATH="$HOME/.config/skill-bill/config.json"
```

```fish
# ~/.config/fish/config.fish
set -gx SKILL_BILL_CONFIG_PATH "$HOME/.config/skill-bill/config.json"
```

Then move your existing config once:

```bash
mkdir -p ~/.config/skill-bill
mv ~/.skill-bill/config.json ~/.config/skill-bill/config.json
```

Notes:

- The variable must be present in whatever environment runs `install.sh` and `skill-bill`. Shells
  launched outside your profile fall back to the default path, so run installs from a shell that
  sources the export.
- As a one-off alternative, `SKILL_BILL_SKIP_PREINSTALL_UNINSTALL=1 ./install.sh …` skips the wipe
  and leaves `~/.skill-bill/config.json` in place. That is intended for dev iteration; the
  relocation above is the durable choice.

## Source Directory Layout

The directory referenced by `path` holds one manifest plus the add-on markdown files it references:

```
acme-review-addon/
├── addon-manifest.yaml
└── acme-review.md
```

Every `.md` file referenced by a pointer target must exist in this directory. The files are copied
verbatim into the installed pack's `addons/` directory.

## addon-manifest.yaml Format

The manifest is a fragment of a platform pack's manifest: it declares `addon_usage` and `pointers`,
each keyed by a **skill-relative directory** that the target pack already declares (its baseline,
area, and quality-check dirs).

```yaml
addon_usage:
  code-review/bill-ios-code-review:
    - slug: acme
      entrypoint: acme-review.md
pointers:
  code-review/bill-ios-code-review:
    - name: acme-review.md
      target: acme-review.md
```

Rules the overlay enforces (loud-fail on violation):

- **`addon_usage` keys must be declared skill directories** of the installed pack.
- **`entrypoint` and every `companion_pointers` entry must name a pointer** declared under the same
  directory, and that pointer's target must resolve under the pack's `addons/`.
- **Pointer `name`** is a bare markdown filename (`*.md`, no path separators, no `..`).
- **Pointer `target`** is auto-rewritten to the canonical
  `platform-packs/<platform>/addons/<file>.md` form. You may write just the bare filename
  (`acme-review.md`) and it is expanded for you; a fully-qualified target is accepted as long as it
  points at the same flat location.
- **Targets must be flat** — directly under `addons/`, with no subdirectories.
- **Only `name`/`target`** are allowed on pointer entries and only
  `slug`/`entrypoint`/`companion_pointers` on addon-usage entries; unexpected keys are rejected.
- **Add-on `slug`** must match `^[a-z][a-z0-9]*(-[a-z0-9]+)*$`.

## Worked Example

Overlay a private `acme` review add-on onto the installed `ios` pack.

1. Create the source directory:

   ```
   ~/dev/acme-review-addon/
   ├── addon-manifest.yaml
   └── acme-review.md
   ```

   `addon-manifest.yaml`:

   ```yaml
   addon_usage:
     code-review/bill-ios-code-review:
       - slug: acme
         entrypoint: acme-review.md
   pointers:
     code-review/bill-ios-code-review:
       - name: acme-review.md
         target: acme-review.md
   ```

2. Register it in `~/.skill-bill/config.json`:

   ```json
   {
     "external_addon_sources": [
       { "path": "~/dev/acme-review-addon", "platform": "ios" }
     ]
   }
   ```

3. Preview what will resolve (optional dry run):

   ```bash
   skill-bill config resolve-external-addons
   # ios	/home/you/dev/acme-review-addon
   ```

4. Install. The overlay applies automatically:

   ```bash
   ./install.sh
   ```

   `acme-review.md` is copied into the installed `ios` pack's `addons/`, and its `addon_usage` and
   `pointers` entries are appended to the installed `platform.yaml`. The `ios` review flow now picks
   up the `acme` add-on exactly like a pack-owned one.

## Commands

| Command | Purpose |
|---------|---------|
| `skill-bill config resolve-external-addons` | Read-only. Lists resolved `platform<TAB>path` entries from the config, or fails loudly on a malformed config. |
| `skill-bill install apply-external-addons` | Applies the overlay onto installed packs. Runs automatically during `./install.sh`; you rarely invoke it directly. Options: `--repo-root` (default `.`) and `--platform-packs` (default `<repo-root>/platform-packs`). |

## Guarantees And Failure Modes

- **Idempotent.** Re-applying never duplicates entries; wiped add-on files are restored on the next
  install.
- **Not-installed platform is skipped**, with a warning, not a failure.
- **Collisions loud-fail.** A pointer name, pointer target basename, or add-on slug that collides
  with a pack-owned entry — or with another external source targeting the same platform and skill
  directory — aborts the overlay rather than silently overwriting. Sources targeting *different*
  platforms never collide, even if they share a skill-relative directory and pointer name.
- **Validate-before-write atomicity.** All sources are validated before any file is written, so a
  validation failure in a later source leaves earlier sources unapplied and the installed manifests
  untouched. A mid-write I/O error (disk/permission) can leave a partially applied state; it
  self-heals on the next install.
- **The installed `platform.yaml` is the merge target**, re-adopted from upstream on each reconcile.
  Comments and key ordering in that installed copy are not preserved across an overlay; your source
  files and this config are the source of truth.
