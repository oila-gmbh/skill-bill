from __future__ import annotations

from pathlib import Path
import os
import shlex
import subprocess
import sys


# Installed Kotlin runs through packaged Gradle application distributions:
# runtime-kotlin/runtime-cli/build/install/runtime-cli/bin/runtime-cli
# runtime-kotlin/runtime-mcp/build/install/runtime-mcp/bin/runtime-mcp.
# Local development can still override those paths with SKILL_BILL_KOTLIN_CLI
# or SKILL_BILL_KOTLIN_MCP.
KOTLIN_CLI_ENV = "SKILL_BILL_KOTLIN_CLI"
KOTLIN_MCP_ENV = "SKILL_BILL_KOTLIN_MCP"


class MissingKotlinDistributionError(RuntimeError):
  pass


def repo_root() -> Path:
  return Path(__file__).resolve().parents[1]


def runtime_kotlin_root() -> Path:
  return repo_root() / "runtime-kotlin"


def packaged_cli_bin(root: Path | None = None) -> Path:
  base = root or repo_root()
  return base / "runtime-kotlin" / "runtime-cli" / "build" / "install" / "runtime-cli" / "bin" / "runtime-cli"


def packaged_mcp_bin(root: Path | None = None) -> Path:
  base = root or repo_root()
  return base / "runtime-kotlin" / "runtime-mcp" / "build" / "install" / "runtime-mcp" / "bin" / "runtime-mcp"


def require_packaged_bin(path: Path, gradle_task: str) -> Path:
  if path.is_file() and os.access(path, os.X_OK):
    return path
  raise MissingKotlinDistributionError(
    "Packaged Kotlin runtime distribution not found: "
    f"{path}. Run '(cd runtime-kotlin && ./gradlew {gradle_task})' "
    "or set the local development override environment variable."
  )


def kotlin_process_environment(environment: dict[str, str] | None = None) -> dict[str, str]:
  process_env = os.environ.copy()
  if environment is not None:
    process_env.update(environment)
  home = process_env.get("HOME", "").strip()
  if home:
    existing_opts = process_env.get("JAVA_OPTS", "").strip()
    user_home_opt = f"-Duser.home={home}"
    if "-Duser.home=" not in existing_opts:
      process_env["JAVA_OPTS"] = f"{existing_opts} {user_home_opt}".strip()
  return process_env


def selected_runtime(environment: dict[str, str] | None = None) -> str:
  return "kotlin"


def kotlin_cli_command(argv: list[str], environment: dict[str, str] | None = None) -> list[str]:
  env = environment or os.environ
  override = env.get(KOTLIN_CLI_ENV, "").strip()
  if override:
    return shlex.split(override) + argv
  return [str(require_packaged_bin(packaged_cli_bin(), ":runtime-cli:installDist"))] + argv


def selected_mcp_runtime(environment: dict[str, str] | None = None) -> str:
  return "kotlin"


def kotlin_mcp_command(environment: dict[str, str] | None = None) -> list[str]:
  env = environment or os.environ
  override = env.get(KOTLIN_MCP_ENV, "").strip()
  if override:
    return shlex.split(override)
  return [str(require_packaged_bin(packaged_mcp_bin(), ":runtime-mcp:installDist"))]


def kotlin_cli_main(argv: list[str], environment: dict[str, str] | None = None) -> int:
  env = environment or os.environ
  run_kwargs: dict[str, object] = {
    "cwd": runtime_kotlin_root(),
    "env": kotlin_process_environment(env),
    "text": False,
    "check": False,
  }
  if sys.stdin.isatty():
    run_kwargs["stdin"] = subprocess.DEVNULL
  else:
    run_kwargs["stdin"] = sys.stdin.buffer
  try:
    command = kotlin_cli_command(argv, env)
  except MissingKotlinDistributionError as error:
    print(str(error), file=sys.stderr)
    return 2
  process = subprocess.run(command, **run_kwargs)
  return int(process.returncode)


def main(argv: list[str] | None = None) -> int:
  args = list(sys.argv[1:] if argv is None else argv)
  return kotlin_cli_main(args)


def mcp_main() -> None:
  try:
    command = kotlin_mcp_command()
  except MissingKotlinDistributionError as error:
    print(str(error), file=sys.stderr)
    raise SystemExit(2)
  raise SystemExit(subprocess.run(
    command,
    cwd=runtime_kotlin_root(),
    env=kotlin_process_environment(),
    check=False,
  ).returncode)


if __name__ == "__main__":
  raise SystemExit(main())
