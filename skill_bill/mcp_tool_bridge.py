from __future__ import annotations

import json
import sys

from skill_bill import mcp_server


def main(argv: list[str] | None = None) -> int:
  args = list(sys.argv[1:] if argv is None else argv)
  if len(args) != 1:
    print("Usage: python -m skill_bill.mcp_tool_bridge <tool-name>", file=sys.stderr)
    return 2

  tool_name = args[0]
  tool = getattr(mcp_server, tool_name, None)
  if tool is None or not callable(tool):
    print(f"Unknown MCP tool: {tool_name}", file=sys.stderr)
    return 2

  raw_payload = sys.stdin.read().strip()
  arguments = json.loads(raw_payload) if raw_payload else {}
  if not isinstance(arguments, dict):
    print("MCP bridge arguments must be a JSON object.", file=sys.stderr)
    return 2

  result = tool(**arguments)
  json.dump(result, sys.stdout, sort_keys=True)
  sys.stdout.write("\n")
  return 0


if __name__ == "__main__":
  raise SystemExit(main())
