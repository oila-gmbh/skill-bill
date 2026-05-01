# Releasing Skill Bill

Skill Bill uses tag-driven GitHub Releases.

The release contract is:

- create an annotated SemVer tag such as `v0.4.0`
- push the tag to GitHub
- let the `Release` workflow rerun validation and publish the GitHub Release

Pre-release tags such as `v0.5.0-rc.1` are also supported and publish GitHub prereleases.

## Release notes

### SKILL-32 Python Runtime Retirement

Skill Bill now uses packaged Kotlin as the only normal CLI and MCP runtime.
Rollback is to install the previous Skill Bill release, not to select a Python
runtime.

Removed environment variables:

- `SKILL_BILL_RUNTIME`
- `SKILL_BILL_MCP_RUNTIME`

Removed files:

- `skill_bill/cli.py`
- `skill_bill/mcp_server.py`
- `scripts/mcp_server_start.sh`
- legacy Python runtime modules for review, learnings, workflow telemetry,
  quality-check, PR description, and SQLite runtime support

## Versioning policy

Skill Bill should stay on pre-1.0 SemVer until the install surface, taxonomy, and stable entry points feel settled.

- bump `patch` for docs-only work, validator fixes, and non-breaking tooling or installer fixes
- bump `minor` for new skills, new platform coverage, new routing behavior, or other user-visible capability additions
- reserve `major` for intentional breaking changes to taxonomy, install behavior, or stable entry points

## Release checklist

1. Make sure the release commit is on `main`.
2. Run the local checks:

   ```bash
   python3 -m unittest discover -s tests
   npx --yes agnix --strict .
   python3 scripts/validate_agent_configs.py
   ```

3. Pick the next version tag.
4. Create an annotated tag:

   ```bash
   git tag -a v0.x.y -m "Release v0.x.y"
   ```

5. Push the tag:

   ```bash
   git push origin v0.x.y
   ```

6. Confirm the `Release` workflow succeeds and the GitHub Release appears with generated notes.

## Installing from a release

If you want a stable install target instead of following `main`, clone the repo at a tag and run the normal installer:

```bash
TAG=v0.x.y
git clone --branch "$TAG" --depth 1 <this-repo> ~/Development/skill-bill
cd ~/Development/skill-bill
./install.sh
```
