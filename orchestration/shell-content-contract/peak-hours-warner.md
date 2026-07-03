# Peak-Hours Warner

This sidecar applies to `bill-feature`, `bill-feature-task`, and the
feature-task runtime/prose/subtask-runner launch surfaces.

After any required update check and before intake, confirmation, workflow open,
or child continuation, check the current local time. Z.AI peak hours are daily
from 14:00 inclusive to 18:00 exclusive.

If the current local time is outside that window, do nothing.

If the current local time is inside that window, inspect every agent/model path
that this launch is about to use:

- the currently executing agent and model
- `--agent`
- `--agent-override`
- each `--phase-agent <phase-id>=<agent-id>` entry
- `parallel-review:<agent>` / `--parallel-review-agent`

Treat `zcode` as GLM unconditionally. Treat any other agent, including
`opencode`, as GLM only when explicit visible model metadata or an explicit model
name says GLM. Do not infer GLM from the agent name alone.

When at least one launch path is known to be GLM, tell the user:

```text
Z.AI/GLM peak hours are 14:00-18:00; this launch is inside peak hours.
```

When different launch paths use different agents, include the known GLM path or
paths in the same notice, for example `GLM path: --phase-agent review=zcode`.
