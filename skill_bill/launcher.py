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
# or SKILL_BILL_KOTLIN_MCP while the Python shim is still present.
RUNTIME_ENV = "SKILL_BILL_RUNTIME"
MCP_RUNTIME_ENV = "SKILL_BILL_MCP_RUNTIME"
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


def selected_runtime(environment: dict[str, str] | None = None) -> str:
  env = environment or os.environ
  return env.get(RUNTIME_ENV, "kotlin").strip().lower() or "kotlin"


def kotlin_cli_command(argv: list[str], environment: dict[str, str] | None = None) -> list[str]:
  env = environment or os.environ
  override = env.get(KOTLIN_CLI_ENV, "").strip()
  if override:
    return shlex.split(override) + argv
  return [str(require_packaged_bin(packaged_cli_bin(), ":runtime-cli:installDist"))] + argv


def selected_mcp_runtime(environment: dict[str, str] | None = None) -> str:
  env = environment or os.environ
  return env.get(MCP_RUNTIME_ENV, "kotlin").strip().lower() or "kotlin"


def kotlin_mcp_command(environment: dict[str, str] | None = None) -> list[str]:
  env = environment or os.environ
  override = env.get(KOTLIN_MCP_ENV, "").strip()
  if override:
    return shlex.split(override)
  return [str(require_packaged_bin(packaged_mcp_bin(), ":runtime-mcp:installDist"))]


def python_cli_main(argv: list[str] | None = None) -> int:
  from skill_bill.cli import main as cli_main

  return cli_main(argv)


def kotlin_cli_main(argv: list[str], environment: dict[str, str] | None = None) -> int:
  env = environment or os.environ
  stdin_payload = None
  if not sys.stdin.isatty():
    stdin_payload = sys.stdin.buffer.read()
  run_kwargs: dict[str, object] = {
    "cwd": runtime_kotlin_root(),
    "text": False,
    "check": False,
  }
  if stdin_payload is None:
    run_kwargs["stdin"] = subprocess.DEVNULL
  else:
    run_kwargs["input"] = stdin_payload
  try:
    command = kotlin_cli_command(argv, env)
  except MissingKotlinDistributionError as error:
    print(str(error), file=sys.stderr)
    return 2
  process = subprocess.run(command, **run_kwargs)
  return int(process.returncode)


def python_mcp_main() -> None:
  from skill_bill.mcp_server import main as python_main

  python_main()


def main(argv: list[str] | None = None) -> int:
  args = list(sys.argv[1:] if argv is None else argv)
  runtime = selected_runtime()
  if runtime == "python":
    return python_cli_main(args)
  if runtime == "kotlin":
    return kotlin_cli_main(args)
  print(
    f"Unsupported {RUNTIME_ENV}={runtime!r}; expected 'kotlin' or 'python'.",
    file=sys.stderr,
  )
  return 2


def mcp_main() -> None:
  runtime = selected_mcp_runtime()
  if runtime not in {"python", "kotlin"}:
    print(
      f"Unsupported {MCP_RUNTIME_ENV}={runtime!r}; expected 'python' or 'kotlin'.",
      file=sys.stderr,
    )
    raise SystemExit(2)
  if runtime == "kotlin":
    try:
      command = kotlin_mcp_command()
    except MissingKotlinDistributionError as error:
      print(str(error), file=sys.stderr)
      raise SystemExit(2)
    raise SystemExit(subprocess.run(
      command,
      cwd=runtime_kotlin_root(),
      check=False,
    ).returncode)

  python_mcp_main()


if __name__ == "__main__":
  raise SystemExit(main())
