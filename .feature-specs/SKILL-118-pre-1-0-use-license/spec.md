---
status: Ready for implementation
issue_key: SKILL-118
source: inline user request
---

# SKILL-118: Pre-1.0 use-only license with stable-release commercial cutoff

## Outcome

Starting with release `v0.1.2`, Skill Bill is distributed under one custom,
source-available, use-only license. The license permits anyone to download,
install, and execute the unmodified software for any lawful purpose, including
commercial purposes, until Skill Bill's first full stable `v1.0.0` release.

When that stable-release event occurs, the public grant for every covered
pre-1.0 version automatically narrows: commercial use ends, while personal use
and unpaid individual use to contribute to qualifying open-source projects
continue. Modification and redistribution of Skill Bill are not granted either
before or after the event. The repository, documentation, release process, and
distributed artifacts communicate the same version boundary and permissions.

## Background / Problem

Skill Bill currently uses a customized PolyForm Noncommercial 1.0.0 license.
PolyForm grants modification and distribution rights for permitted purposes
and blocks commercial use immediately, so it does not express the intended
product policy:

- pre-1.0 adoption from `v0.1.2` should include free-of-charge commercial use;
- users should receive execution rights, not rights to modify or redistribute
  Skill Bill;
- the first full stable `v1.0.0` release should automatically end commercial
  use of all covered pre-1.0 versions; and
- natural persons should retain personal and unpaid open-source contribution
  use after that event.

This change is prospective. It cannot withdraw rights already received for
older source revisions or releases under MIT or PolyForm. In particular,
`v0.1.0` and `v0.1.1` remain under the terms shipped with those releases. The
new license must state this boundary plainly rather than implying retroactive
relicensing.

The repository is public on GitHub. GitHub's platform terms independently grant
users limited rights to view and reproduce public content through GitHub's
forking functionality. The project license cannot revoke separately granted
platform rights. It must therefore say that Skill Bill grants no modification
or redistribution permission while avoiding a false claim that no separate
right can exist. Preventing GitHub-hosted forks absolutely would require a
repository-visibility or hosting-policy decision outside this feature.

## Decided Semantics

### Covered software and version boundary

The custom license applies to Skill Bill release `v0.1.2` and every later
SemVer release below `v1.0.0` that is distributed with the license, together
with post-license-change source snapshots leading to those releases. It does
not replace or restrict licenses already granted for `v0.1.1`, `v0.1.0`, or
earlier repository revisions.

The license is a custom source-available license and must not be described as
open source, free software, MIT, or PolyForm. Use the stable identifier
`LicenseRef-Skill-Bill-Pre-1.0-Use-1.0` wherever a machine-readable custom
identifier is supported and the platform-appropriate `custom` classification
otherwise.

### Stable-release event

The **Stable Release Event** is the first publication by the copyright holder
of a public, non-draft, non-prerelease GitHub Release in the canonical
`oila-gmbh/skill-bill` repository whose tag is exactly `v1.0.0`.

The following do not trigger the event:

- a branch, commit, or local tag named `v1.0.0` without the qualifying GitHub
  Release;
- a draft release;
- a GitHub prerelease;
- release candidates or other prerelease identifiers such as
  `v1.0.0-rc.1`; or
- a release published by a fork or another repository owner.

Once the Stable Release Event occurs, withdrawing, deleting, or marking the
`v1.0.0` release as a prerelease does not restore the expired commercial-use
grant.

### Grant before the Stable Release Event

Before the Stable Release Event, every person and legal entity receives a
non-exclusive, worldwide, royalty-free right to:

1. obtain an unmodified copy of the covered software;
2. make only the transient, installation, execution, and reasonable backup
   copies necessary to exercise the permitted use; and
3. install and execute that unmodified software for any lawful purpose,
   including internal business use, paid work, consulting, managed services,
   and hosted services, provided no copy of Skill Bill is transferred or made
   available to a third party.

The grant is free of charge. It is not a sale, transfer of ownership,
sublicense, or permission to modify or redistribute Skill Bill.

### Grant after the Stable Release Event

At the Stable Release Event, commercial-use permission for all covered
versions ends automatically. A licensee may continue to make the necessary
technical copies and execute an unmodified covered version only for:

- **Personal Use:** use by a natural person solely on their own behalf, not as
  an employee, contractor, agent, or representative of another person or
  organization and not for compensation, revenue, or business advantage; or
- **Open Source Contribution Use:** use by a natural person, without
  compensation and not on behalf of an employer, client, or other
  organization, solely to create, maintain, test, review, document, or
  contribute to software whose source code is publicly available under a
  license approved by the Open Source Initiative.

Open Source Contribution Use concerns what the person uses Skill Bill to work
on. It does not make Skill Bill open source or permit copying Skill Bill into
that project. Paid maintainership, employer-sponsored contributions,
consulting, organization-directed work, and use intended to produce revenue or
business advantage are commercial use and are not permitted after the event.
Charitable, educational, governmental, and other nonprofit organizational use
is not automatically permitted unless it independently fits one of the two
post-event grants above.

After the Stable Release Event, a person or entity whose use no longer
qualifies must stop executing every covered version and remove active,
installed, and deployable copies under its control. One non-executable archival
copy may be retained only when required by applicable law, and may not be used.

### Permanent restrictions

The project license grants no right at any time to:

- modify, translate, adapt, or prepare derivative works from Skill Bill;
- distribute, publish, mirror, sublicense, sell, lease, transfer, bundle, or
  otherwise provide Skill Bill source or binaries to another person;
- remove or alter copyright, license, attribution, or proprietary notices;
- represent Skill Bill as open-source software; or
- use project names, marks, or branding except as reasonably necessary to
  identify the unmodified software.

These restrictions do not purport to cancel a right received under an earlier
license, applicable law, or a separate agreement such as GitHub's
platform-limited public-repository terms. The custom license itself grants no
fork, modification, or redistribution permission.

### User materials and outputs

Using documented extension and input surfaces is permitted and is not a
modification of Skill Bill. A user may create, modify, use, license, and
distribute independently authored specifications, skills, platform packs,
add-ons, native-agent definitions, configuration, prompts, and similar input
material, provided they do not copy a substantial protected portion of Skill
Bill.

The license makes no ownership claim over code, documents, reports, telemetry
exports, patches, or other output produced through permitted use. Users may
use, modify, license, and distribute those outputs, subject to third-party
rights and applicable law. An output that reproduces a protected portion of
Skill Bill remains governed by the Skill Bill license for that portion.

### Third-party software and separate permissions

Third-party components retain their own licenses. The Skill Bill license must
not claim to narrow permissions granted by Apache, Kotlin, Gradle, dependency,
operating-system, or other third-party copyright holders. Existing separately
negotiated commercial or partner authorizations remain valid according to
their own terms.

The license includes acceptance, copyright ownership, reservation of rights,
termination for breach, a reasonable first-breach cure mechanism, severability,
no-waiver, and warranty/liability clauses. The final authored legal text must
be internally consistent, must include the complete definition of every
capitalized term it relies on, and must receive explicit copyright-holder
approval before `v0.1.2` is tagged. External legal review is recommended but is
not represented by repository validation.

## Scope

### 1. Replace the public license prospectively

Replace the current PolyForm-based `LICENSE` with the complete custom license
implementing the decided semantics. The license must carry copyright notice
`Copyright (c) 2026 Braian Gapur`, the custom license name and version, the
`LicenseRef-Skill-Bill-Pre-1.0-Use-1.0` identifier, the `v0.1.2` prospective
boundary, and the exact Stable Release Event.

Do not append the new rules as an informal preamble to another license. The
custom terms are the whole project license for covered releases, while
third-party files that already carry their own notices remain under those
notices.

### 2. Align repository-facing policy

Update the README license badge and license section, `CONTRIBUTING.md`,
`RELEASING.md`, and the team-control-plane roadmap so none retains the current
PolyForm/noncommercial-at-all-times description. Add one concise licensing
document if needed to present the non-authoritative version matrix without
duplicating or paraphrasing away the governing `LICENSE` text.

The public summary must make these points visible without legal inference:

- `v0.1.0` and `v0.1.1`: terms shipped with those releases;
- `v0.1.2` through the last pre-1.0 release, before stable `v1.0.0`: unmodified
  use for any lawful purpose, including commercial use;
- the same covered versions after stable `v1.0.0`: personal and unpaid
  individual open-source contribution use only;
- no modification or redistribution permission from the project license; and
- user-authored materials and outputs remain the user's.

Keep the contribution agreement's explicit grant allowing Braian Gapur to
include contributions in separately licensed commercial versions. Reconcile
its repository-license wording with the automatic narrowing event, and do not
claim a CLA or sign-off process that the repository does not use.

### 3. Carry the license with every distribution

Ensure the complete root license text accompanies every artifact published by
the release workflow:

- CLI runtime-image archives;
- MCP runtime-image archives;
- the skills archive; and
- desktop application distributions/installers using each platform's normal
  license/notices location or installer presentation mechanism.

The included file must be sourced from the root `LICENSE`; generated copies
must not become a second authored source of truth. Build and release tasks must
loud-fail if the root license is absent or the staged license bytes differ.
Checksums continue to cover the final artifacts containing the license.

### 4. Add release-policy guards

Extend release validation so:

1. a release tag `>= v0.1.2` and `< v1.0.0` is rejected unless the source tree
   carries the expected custom-license identifier and prospective boundary;
2. `v0.1.0` and `v0.1.1` remain historical exceptions and are not rewritten;
3. prereleases below `v1.0.0` use the same custom license but do not trigger the
   Stable Release Event;
4. a `v1.0.0` or later release is rejected while the transitional pre-1.0
   license is still presented as the current release's governing license,
   forcing an explicit 1.0 licensing decision before publication; and
5. manual staging releases remain GitHub prereleases and therefore cannot
   accidentally trigger the event.

The guard validates repository and artifact policy; it does not attempt to
phone home, remotely disable installed software, delete user files, or make a
legal-enforceability determination.

### 5. Add focused consistency tests

Add repository-level or Kotlin tests that protect the version matrix, exact
trigger definition, permanent use-only restrictions, post-event personal/open
source contribution definitions, output/extension carve-outs, historical
license disclaimer, custom package identifier, and release-artifact inclusion.
Prefer structured markers or a small policy model over brittle assertions that
duplicate the full legal prose.

## Acceptance Criteria

1. The root `LICENSE` is a complete custom license identified as `LicenseRef-Skill-Bill-Pre-1.0-Use-1.0`, applies prospectively beginning with `v0.1.2`, and does not present Skill Bill as PolyForm, MIT, open source, or free software.
2. Before the Stable Release Event, the license permits every person and legal entity to obtain, install, and execute unmodified covered software for any lawful purpose, explicitly including commercial, consulting, managed-service, and hosted-service use without transfer of a Skill Bill copy.
3. The project license grants no modification, derivative-work, redistribution, publication, mirroring, sublicensing, sale, lease, transfer, or bundling right at any time, apart from technical and backup copies necessary for permitted execution.
4. The Stable Release Event is exactly the first public, non-draft, non-prerelease GitHub Release tagged `v1.0.0` by the copyright holder in `oila-gmbh/skill-bill`; drafts, prereleases, release candidates, bare tags, branches, local artifacts, and fork releases do not trigger it, and later withdrawal does not reverse it.
5. At the Stable Release Event, commercial permission for every covered pre-1.0 version ends automatically and nonqualifying users are required to stop execution and remove active/deployable copies, subject only to a legally required non-executable archival copy.
6. After the event, continued execution is limited to Personal Use and Open Source Contribution Use by natural persons; the latter is unpaid, not performed for an employer/client/organization, and solely supports software publicly available under an OSI-approved license. Paid, sponsored, employer-directed, consulting, and business-advantage uses are excluded.
7. The license expressly preserves the user's rights in independently authored specifications, skills, packs, add-ons, agent definitions, configuration, prompts, and generated outputs while retaining Skill Bill's license over any protected Skill Bill material reproduced within them.
8. The license and public documentation acknowledge that earlier MIT/PolyForm grants, applicable law, separate agreements, GitHub platform-limited public-repository rights, and third-party component licenses are not retroactively withdrawn or narrowed.
9. README, contribution guidance, release guidance, roadmap licensing language, badge/package metadata, and any dedicated licensing summary present one consistent version matrix and link to `LICENSE` as the governing text.
10. CLI and MCP archives, the skills archive, and every desktop distribution/installer carry the complete root license from one authored source; focused build tests detect absence or byte drift, and published checksums cover the licensed artifacts.
11. Release validation rejects pre-1.0 releases from `v0.1.2` onward when the custom policy is missing, preserves `v0.1.0`/`v0.1.1` as historical exceptions, treats every staging/RC build as non-triggering, and rejects `v1.0.0+` while the transitional license is still presented as that release's current license.
12. The complete repository quality gate passes, and the copyright holder explicitly approves the final license text before the `v0.1.2` tag is created.

## Non-goals / Constraints

- Do not change or delete the historical `v0.1.0` or `v0.1.1` tags, rewrite Git history, or claim that earlier MIT or PolyForm permissions have expired.
- Do not choose the license for `v1.0.0` or later; this feature only ensures that publishing `v1.0.0` requires a deliberate replacement of the transitional current-release license.
- Do not implement activation servers, license keys, telemetry-based enforcement, remote shutdown, automatic deletion, degraded old-version behavior, or other technical-use controls.
- Do not prohibit use, modification, or distribution of independently authored user materials and outputs merely because Skill Bill helped produce them.
- Do not relicense Gradle wrappers, dependencies, bundled runtimes, or other third-party material contrary to their existing notices.
- Do not label the custom license OSI-approved or use an SPDX identifier reserved for another license.
- Do not promise that repository checks establish legal enforceability or replace review by qualified counsel.
- Preserve authored/generated boundaries: release copies may be generated from root `LICENSE`, but generated license copies and packaged artifacts are not committed as authored source.
- Comments added during implementation must follow the repository comments policy.

## Validation Strategy

- Focused license-policy tests covering semantic markers, complete definitions,
  the `v0.1.2` lower boundary, the `v1.0.0` event, pre/post-event grants,
  historical disclaimers, and documentation consistency.
- Release-ref acceptance/rejection tests for `v0.1.1`, `v0.1.2`, a normal
  pre-1.0 release, `v1.0.0-rc.1`, manual staging, exact `v1.0.0`, and later
  stable tags with both transitional and deliberately replaced license states.
- Archive/package tests that inspect CLI, MCP, skills, and desktop outputs and
  compare their included license bytes with root `LICENSE`.
- A manual legal-text review checklist confirming every capitalized term is
  defined, the grant and restrictions do not conflict, the event is
  objectively observable, and user-material/output language is separated from
  the covered software.
- Run the full repository gate:

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```

## Expected Affected Areas

- `LICENSE`
- `README.md`
- `CONTRIBUTING.md`
- `RELEASING.md`
- `docs/team-control-plane-roadmap.md`
- optional user-facing licensing summary under `docs/`
- `.github/workflows/release.yml`
- `scripts/validate_release_ref` and its tests
- runtime CLI/MCP archive packaging
- desktop installer/package resources
- Arch/custom package metadata
- focused repository licensing and distribution tests

## Next Path

Run bill-feature on `.feature-specs/SKILL-118-pre-1-0-use-license/spec.md`.
