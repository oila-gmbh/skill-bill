#!/usr/bin/env python3

from __future__ import annotations

from dataclasses import dataclass
import argparse
import json
import os
import re
import sys


SEMVER_TAG_PATTERN = re.compile(
  r"^v"
  r"(?P<major>0|[1-9]\d*)\."
  r"(?P<minor>0|[1-9]\d*)\."
  r"(?P<patch>0|[1-9]\d*)"
  r"(?:-(?P<prerelease>"
  r"(?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*)"
  r"(?:\.(?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*))*"
  r"))?"
  r"(?:\+(?P<build>[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$"
)


@dataclass(frozen=True)
class ReleaseRef:
  tag: str
  version: str
  prerelease: bool


def normalize_ref(value: str) -> str:
  candidate = value.strip()
  if candidate.startswith("refs/tags/"):
    return candidate.removeprefix("refs/tags/")
  return candidate


def parse_release_ref(value: str) -> ReleaseRef:
  candidate = normalize_ref(value)
  match = SEMVER_TAG_PATTERN.fullmatch(candidate)
  if not match:
    raise ValueError(
      "Release tag must match vMAJOR.MINOR.PATCH with optional SemVer prerelease/build metadata."
    )

  return ReleaseRef(
    tag=candidate,
    version=candidate.removeprefix("v"),
    prerelease=bool(match.group("prerelease")),
  )


def resolve_ref(cli_value: str | None) -> str:
  if cli_value:
    return cli_value

  for environment_key in ("GITHUB_REF_NAME", "GITHUB_REF"):
    value = os.environ.get(environment_key)
    if value:
      return value

  raise ValueError("No release ref supplied. Pass a tag or set GITHUB_REF_NAME.")


def write_github_output(path: str, release_ref: ReleaseRef) -> None:
  with open(path, "a", encoding="utf-8") as handle:
    handle.write(f"tag={release_ref.tag}\n")
    handle.write(f"version={release_ref.version}\n")
    handle.write(f"prerelease={'true' if release_ref.prerelease else 'false'}\n")


def main(argv: list[str] | None = None) -> int:
  parser = argparse.ArgumentParser(
    description="Validate a release tag and emit metadata for GitHub Actions."
  )
  parser.add_argument("ref", nargs="?", help="Tag or refs/tags/... reference to validate.")
  parser.add_argument(
    "--github-output",
    help="Optional file path where GitHub Actions step outputs should be appended.",
  )
  args = parser.parse_args(argv)

  try:
    release_ref = parse_release_ref(resolve_ref(args.ref))
  except ValueError as error:
    print(str(error), file=sys.stderr)
    return 1

  if args.github_output:
    write_github_output(args.github_output, release_ref)

  print(
    json.dumps(
      {
        "tag": release_ref.tag,
        "version": release_ref.version,
        "prerelease": release_ref.prerelease,
      },
      indent=2,
    )
  )
  return 0


if __name__ == "__main__":
  raise SystemExit(main())
