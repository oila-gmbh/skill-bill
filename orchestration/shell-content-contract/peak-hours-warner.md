# Peak-Hours Warner

This sidecar applies to `bill-feature`, `bill-feature-task`, and the
feature-task runtime/prose/subtask-runner launch surfaces.

After any required update check and before intake, confirmation, workflow open,
or child continuation, read the optional repo-local operator config at
`.skill-bill/config.yaml`.

If the config file is absent, or if it does not contain a `peak_hours` section,
skip this warning silently. Do not print a notice, infer fallback defaults, or
ask the user to configure peak-hour windows.

The optional config shape is:

```yaml
peak_hours:
  timezone: "<iana-timezone>"
  windows:
    - start: "<local-start-time>"
      end: "<local-end-time>"
  matches:
    - provider: "<provider-label>"
      model: "<model-label>"
```

The `timezone` value defines how the configured windows are interpreted. Each
window is an inclusive start and exclusive end in that timezone. A match applies
when the visible provider and model metadata for a launch path match a configured
provider/model label.

If the current local time is outside every configured window, do nothing.

If the current local time is inside a configured window, inspect every
agent/model path that this launch is about to use:

- the currently executing agent and model
- `--agent`
- `--agent-override`
- each `--phase-agent <phase-id>=<agent-id>` entry
- `parallel-review:<agent>` / `--parallel-review-agent`

Match only against provider/model labels configured in `.skill-bill/config.yaml`.
Do not infer a model family from an agent name alone.

When at least one launch path is known to match the configured provider/model
labels, tell the user:

```text
Configured peak-hour window is active; this launch uses a configured provider/model match.
```

When different launch paths use different agents, include the known matching
path or paths in the same notice, for example
`matched path: --phase-agent review=<agent-id>`.
