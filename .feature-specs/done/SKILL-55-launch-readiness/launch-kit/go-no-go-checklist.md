# Launch go/no-go checklist

Every item traces to a concrete subtask-1-to-5 deliverable. Facts trace to the
[ground-truth fact sheet](README.md).

Legend: `[x]` shipped/verified, `[ ]` **OPEN gate** (must close before the gated
launch). Open gates are surfaced honestly — they are not pre-checked.

---

## S1 — Per-OS prebuilt runtime images

- [x] Self-contained jlink `runtime-cli` + `runtime-mcp` archives exist for the four
  host tokens (`macos-arm64`, `macos-x64`, `windows-x64`, `linux-x64`), no system JDK
  required, each with a `.sha256`. *(S1)*
- [ ] **OPEN:** runtime images for all four hosts are actually **published on a public
  release** (depends on S3 + a real tag — see below). *(S1 × S3)*

## S2 — Per-OS prebuilt desktop installers

- [x] `jpackage` desktop installers produced per OS — `.dmg` / `.msi` / `.deb` / `.rpm`,
  bundled JRE, version-named `SkillBill-<version>-<os>-<arch>.<ext>` + `.sha256`. *(S2)*
- [x] From-source `--with-desktop-app` path preserved. *(S2)*
- [x] Desktop installers are **unsigned for v1** (recorded decision; the open-anyway FAQ
  answer is ready in [objection-faq.md](objection-faq.md)). *(S2)*
- [ ] **OPEN:** `.rpm` / `.dmg` / `.msi` desktop extraction is **unexercised by CI** —
  only `.deb` on `linux-x64` has been exercised. The other three formats must be
  verified before the gated launch. *(S2 × S3)*

## S3 — Tag-driven release CI

- [x] `.github/workflows/release.yml` matrix (macOS / Windows / Ubuntu runners) builds
  and attaches runtime images + desktop installers + `.sha256` to the GitHub Release;
  `rc.N` → prerelease; fail-closed validation gate. *(S3 — shipped, commit `74403c4`)*
- [ ] **OPEN:** **no public stable tag/release has been cut yet.** The workflow exists
  but has not produced a published stable release. This is the master gate — Reddit and
  Product Hunt both depend on a real, downloadable release. *(S3)*
- [ ] **OPEN:** a release run has been observed to **attach all four hosts' artifacts
  with matching checksums** end to end. *(S3)*

## S4 — Installer (`install.sh`)

- [x] Prebuilt-by-default with `--from-source` fallback and auto-fallback for
  unsupported hosts. *(S4)*
- [x] Prebuilt desktop is **extracted**, not system-installed; desktop default is
  display-gated. *(S4)*
- [x] `print_install_plan` shows the plan before any mutation. *(S4)*
- [x] `--desktop-app-only` later-add path exists; unsigned hint reused verbatim. *(S4)*
- [ ] **OPEN:** **clean-machine install is unverified.** `./install.sh` has not been run
  end-to-end on a fresh machine per supported OS (pulling the *published* release, not a
  local build). This must pass on at least one clean host per OS family before launch.
  *(S4 × S1 × S3)*

## S5 — README front door + demo assets

- [x] README front door reordered: hook → demo → 60s quickstart → prereqs → complexity
  paragraph. *(S5)*
- [x] Demo **placeholder SVG** + **storyboard** + **capture-instructions** committed.
  *(S5)*
- [x] Install docs lead with the prebuilt default and `--release` pinning. *(S5)*
- [ ] **OPEN:** the **recorded motion demo does not exist yet** (storyboarded only). The
  README still embeds the placeholder. Recording + committing the real demo and swapping
  the README embed is a **hard gate for Product Hunt** (and nice-to-have, not required,
  for the Reddit soft launch). *(S5)*

---

## Go / No-Go summary

**Reddit soft launch — GO when:**

- The S3 master gate closes: a **public stable release is cut** with all four hosts'
  artifacts + checksums attached.
- **Clean-machine install verified** on at least one host per OS family (S4 gate).
- (Recorded demo is *nice to have* here, not required — the post links the repo, not a
  video.)

**Product Hunt launch — GO only when all Reddit gates close AND:**

- `.rpm` / `.dmg` / `.msi` desktop extraction verified (S2 gate).
- The **recorded motion demo exists and is committed**, README embed swapped off the
  placeholder (S5 gate).

If any OPEN item above is still unchecked, the corresponding launch is a **NO-GO**.
