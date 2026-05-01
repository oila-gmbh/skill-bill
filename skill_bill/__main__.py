from __future__ import annotations

import argparse
from pathlib import Path
import sys

from skill_bill import install
from skill_bill.launcher import main as launcher_main


def install_main(argv: list[str]) -> int:
  parser = argparse.ArgumentParser(prog="python -m skill_bill install")
  subcommands = parser.add_subparsers(dest="command", required=True)

  agent_path = subcommands.add_parser("agent-path")
  agent_path.add_argument("agent")

  subcommands.add_parser("detect-agents")

  link_skill = subcommands.add_parser("link-skill")
  link_skill.add_argument("--source", required=True)
  link_skill.add_argument("--target-dir", required=True)
  link_skill.add_argument("--agent", default="manual")

  args = parser.parse_args(argv)
  if args.command == "agent-path":
    paths = install.agent_paths()
    if args.agent not in paths:
      parser.error(f"Unknown agent '{args.agent}'.")
    print(paths[args.agent])
    return 0
  if args.command == "detect-agents":
    for target in install.detect_agents():
      print(f"{target.name}\t{target.path}")
    return 0
  if args.command == "link-skill":
    install.install_skill(
      Path(args.source),
      [install.AgentTarget(name=args.agent, path=Path(args.target_dir))],
    )
    return 0
  parser.error("Unknown install command.")
  return 2


def main(argv: list[str] | None = None) -> int:
  args = list(sys.argv[1:] if argv is None else argv)
  if args[:1] == ["install"]:
    return install_main(args[1:])
  return launcher_main(args)


raise SystemExit(main())
