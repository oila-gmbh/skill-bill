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
  subcommands.add_parser("codex-agents-path")
  subcommands.add_parser("detect-codex-agents")
  subcommands.add_parser("opencode-agents-path")
  subcommands.add_parser("detect-opencode-agents")

  link_skill = subcommands.add_parser("link-skill")
  link_skill.add_argument("--source", required=True)
  link_skill.add_argument("--target-dir", required=True)
  link_skill.add_argument("--agent", default="manual")

  link_codex_agents = subcommands.add_parser("link-codex-agents")
  link_codex_agents.add_argument("--platform-packs", required=True)

  unlink_codex_agents = subcommands.add_parser("unlink-codex-agents")
  unlink_codex_agents.add_argument("--platform-packs", required=True)

  link_opencode_agents = subcommands.add_parser("link-opencode-agents")
  link_opencode_agents.add_argument("--platform-packs", required=True)

  unlink_opencode_agents = subcommands.add_parser("unlink-opencode-agents")
  unlink_opencode_agents.add_argument("--platform-packs", required=True)

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
  if args.command == "codex-agents-path":
    print(install.codex_agents_path())
    return 0
  if args.command == "detect-codex-agents":
    target = install.detect_codex_agents_target()
    if target is not None:
      print(f"{target.name}\t{target.path}")
    return 0
  if args.command == "opencode-agents-path":
    print(install.opencode_agents_path())
    return 0
  if args.command == "detect-opencode-agents":
    target = install.detect_opencode_agents_target()
    if target is not None:
      print(f"{target.name}\t{target.path}")
    return 0
  if args.command == "link-skill":
    install.install_skill(
      Path(args.source),
      [install.AgentTarget(name=args.agent, path=Path(args.target_dir))],
    )
    return 0
  if args.command == "link-codex-agents":
    target = install.detect_codex_agents_target()
    if target is None:
      return 0
    toml_files = install.discover_codex_agent_tomls(Path(args.platform_packs))
    for toml_file in toml_files:
      install.install_codex_agent_toml(toml_file, target)
    return 0
  if args.command == "unlink-codex-agents":
    install.uninstall_codex_agent_tomls(Path(args.platform_packs))
    return 0
  if args.command == "link-opencode-agents":
    target = install.detect_opencode_agents_target()
    if target is None:
      return 0
    md_files = install.discover_opencode_agent_mds(Path(args.platform_packs))
    for md_file in md_files:
      install.install_opencode_agent_md(md_file, target)
    return 0
  if args.command == "unlink-opencode-agents":
    install.uninstall_opencode_agent_mds(Path(args.platform_packs))
    return 0
  parser.error("Unknown install command.")
  return 2


def main(argv: list[str] | None = None) -> int:
  args = list(sys.argv[1:] if argv is None else argv)
  if args[:1] == ["install"]:
    return install_main(args[1:])
  return launcher_main(args)


raise SystemExit(main())
