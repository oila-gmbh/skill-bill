# Contributing to Skill Bill

Skill Bill is pre-1.0 and solo-maintained. Contributions are welcome. A few
things to know before contributing:

## Licensing

By submitting a contribution, you grant Braian Gapur a perpetual, worldwide,
non-exclusive, royalty-free, irrevocable right to reproduce, modify, prepare
derivative works from, incorporate, publish, distribute, sublicense, and
relicense that contribution as part of public Skill Bill releases and
separately licensed releases. You represent that you have the authority to
grant those rights. This contributor grant gives the copyright holder rights in
your contribution; it does not change the project [LICENSE](LICENSE), which
governs rights in Skill Bill itself.

The non-authoritative [licensing summary](docs/licensing.md) gives the same
version matrix as the public release guidance: v0.1.0 and v0.1.1 retain their
shipped terms; releases from v0.1.2 use the custom project license; and the
stable `v1.0.0` Stable Release Event changes free commercial use to free personal and
qualifying open-source-project use, with other commercial use requiring a
purchased Commercial License. The root `LICENSE` text governs. This is not a
CLA or sign-off requirement.

## Quality Gate

Run the full gate before proposing changes that touch the runtime, scaffold,
contracts, or agent-config files:

```bash
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```

`./gradlew check` builds and runs the Kotlin test suite. `validate_agent_configs`
checks generated agent-config manifests for drift. See the
[Validation Gate section in Getting Started](docs/getting-started.md#validation-gate)
for the full description.

## Extension Point: Platform Packs

The documented extension surface is **platform packs** under
`platform-packs/<lang>/`. A pack is a directory with:

- `platform.yaml` — routing signals and pack metadata
- `code-review/` — stack-specific code-review skill definitions per area
- `quality-check/` — stack-specific quality-check skill definitions

Adding a new language pack is additive: drop in `platform-packs/<lang>/` and
the generic `/bill-code-review` and `/bill-code-check` skills start routing to
it automatically — no changes to generic skill files required. See
[Skill Source and Generation Model](docs/skill-source-generation.md) for the
source layout and render contract.

## Extending via the MCP Tool Surface

`skill-bill-mcp` exposes local runtime primitives as structured MCP tools.
These are documented in the
[MCP Server section of Getting Started](docs/getting-started.md#mcp-server).
The tool surface is stable for review, workflow-state, and telemetry
operations; new tools ship as additive extensions.

## Pre-1.0 Caveat

This project is pre-1.0. Top-level skill names, skill taxonomy, and
platform-pack conventions may still change between releases. Before investing
significant effort in a patch that touches these areas, open an issue first
to check whether the affected area is already in flux.

## Issues and PRs

- File bugs, questions, or feature requests at the GitHub issue tracker.
- Open PRs against `main`. A passing quality gate and a clear description of
  what changed and why are the only required conventions.
- No CLA or sign-off ritual required.
