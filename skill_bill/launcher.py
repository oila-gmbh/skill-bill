from __future__ import annotations

from pathlib import Path
import os
import shlex
import subprocess
import sys


RUNTIME_ENV = "SKILL_BILL_RUNTIME"
MCP_RUNTIME_ENV = "SKILL_BILL_MCP_RUNTIME"
KOTLIN_CLI_ENV = "SKILL_BILL_KOTLIN_CLI"
KOTLIN_MCP_ENV = "SKILL_BILL_KOTLIN_MCP"


def repo_root() -> Path:
  return Path(__file__).resolve().parents[1]


def selected_runtime(environment: dict[str, str] | None = None) -> str:
  env = environment or os.environ
  return env.get(RUNTIME_ENV, "kotlin").strip().lower() or "kotlin"


def kotlin_cli_command(argv: list[str], environment: dict[str, str] | None = None) -> list[str]:
  env = environment or os.environ
  override = env.get(KOTLIN_CLI_ENV, "").strip()
  if override:
    return shlex.split(override) + argv
  root = repo_root()
  gradlew = root / "runtime-kotlin" / "gradlew"
  return [
    str(gradlew),
    "-q",
    ":runtime-cli:run",
    "--args",
    shlex.join(argv),
  ]


def selected_mcp_runtime(environment: dict[str, str] | None = None) -> str:
  env = environment or os.environ
  return env.get(MCP_RUNTIME_ENV, "kotlin").strip().lower() or "kotlin"


def kotlin_mcp_command(environment: dict[str, str] | None = None) -> list[str]:
  env = environment or os.environ
  override = env.get(KOTLIN_MCP_ENV, "").strip()
  if override:
    return shlex.split(override)
  root = repo_root()
  gradlew = root / "runtime-kotlin" / "gradlew"
  return [str(gradlew), "-q", ":runtime-mcp:run"]


def python_cli_main(argv: list[str] | None = None) -> int:
  from skill_bill.cli import main as cli_main

  return cli_main(argv)


def kotlin_cli_main(argv: list[str], environment: dict[str, str] | None = None) -> int:
  env = environment or os.environ
  stdin_payload = None
  if not sys.stdin.isatty():
    stdin_payload = sys.stdin.buffer.read()
  run_kwargs: dict[str, object] = {
    "cwd": repo_root() / "runtime-kotlin",
    "text": False,
    "check": False,
  }
  if stdin_payload is None:
    run_kwargs["stdin"] = subprocess.DEVNULL
  else:
    run_kwargs["input"] = stdin_payload
  process = subprocess.run(kotlin_cli_command(argv, env), **run_kwargs)
  return int(process.returncode)


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
    raise SystemExit(subprocess.run(
      kotlin_mcp_command(),
      cwd=repo_root() / "runtime-kotlin",
      check=False,
    ).returncode)

  from skill_bill.mcp_server import main as python_mcp_main

  python_mcp_main()


if __name__ == "__main__":
  raise SystemExit(main())
