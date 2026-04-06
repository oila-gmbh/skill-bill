# Cloudflare Worker telemetry proxy

This example lets Skill Bill clients send telemetry to a small Cloudflare Worker instead of the default hosted relay while keeping the client payload backend-agnostic.

## What it does

1. Accepts `POST` requests containing a JSON body with a `batch` array.
2. Performs lightweight validation on the incoming batch.
3. Adds the server-side `POSTHOG_API_KEY` for this example backend.
4. Forwards the batch to PostHog `/batch/`.

The client payload stays the same privacy-scoped metadata produced by the `skill-bill` CLI and MCP server:

- completed review run snapshots with aggregate finding counts, accepted/rejected finding metadata (finding id, severity, confidence, and outcome type only), and nested learning metadata

It excludes:

- repository identity, branch names, and file paths
- raw review text, finding descriptions, and rejection notes
- learning content (title, rule text, rationale)
- local-only learning bookkeeping events

## Deploy

1. Install Wrangler.
2. Copy `wrangler.toml.example` to a local `wrangler.toml`. That generated file is intentionally ignored and should stay machine-specific.
3. From this directory, set the PostHog project key:

   ```bash
   wrangler secret put POSTHOG_API_KEY
   ```

4. Deploy:

   ```bash
   wrangler deploy
   ```

The example defaults `POSTHOG_HOST` to the US Cloud endpoint. If you use EU Cloud or self-hosted PostHog, update `POSTHOG_HOST` in your local `wrangler.toml` before deploying.

## Add the proxy as the telemetry destination

Set the proxy URL on the machine running Skill Bill:

```bash
export SKILL_BILL_TELEMETRY_PROXY_URL="https://your-worker.your-subdomain.workers.dev"
```

When this variable is set, Skill Bill sends telemetry to your Worker only.

Then keep using the normal telemetry commands:

```bash
skill-bill telemetry status
skill-bill telemetry sync
```

## Notes

- This example is intentionally minimal; it protects the PostHog project key, not the endpoint itself.
- If you expect public traffic, add Cloudflare rate limiting, bot protection, or other filtering on top of this Worker.
- The Skill Bill client always emits the same generic `{"batch": [...]}` payload. If you use another analytics backend, adapt this Worker to translate that batch before forwarding it.
