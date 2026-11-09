# Terminal tab icon API (v1)

An optional, authenticated loopback API for **real leading icons in Terminal tool-window tabs**. It does not change terminal titles, select tabs, activate windows, execute shell commands, or call a model. Requires the bundled Terminal plugin.

## Connect

1. Settings → Tools → Claude Code [Kit] → **Terminal tab icon API** → enable and **Apply**.
2. Click **Copy connection environment** and paste the commands into the intended **local** shell. This sets `CC_KIT_TERMINAL_ICON_URL` and `CC_KIT_TERMINAL_ICON_TOKEN` for that IDE process. The plugin does not change shell configuration or install hooks.
3. The IDE already supplies `$TERM_SESSION_ID`; pass it explicitly on every request. Never target a tab by title, position, or focus.

The endpoint uses the IDE built-in server's actual port:

```text
POST http://127.0.0.1:<port>/api/claude-commit/v1/<instance-uuid>/terminal-icon
Authorization: Bearer <per-process token>
Content-Type: application/json
```

The copied commands use POSIX shell syntax; on Windows, assign the two variables using the syntax of the chosen shell. Credentials are not saved to plugin settings. Restarting the IDE, toggling the API, or **Reset connection and clear icons** invalidates the old connection and clears overrides. Copy fresh commands afterwards. Reset is also recovery if a client loses its lease token.

## Static SVG example

Use `jq` to construct JSON safely rather than interpolating SVG or UUIDs into JSON strings:

```sh
jq -n --arg session "$TERM_SESSION_ID" '{
  id: "set-1", method: "surface.icon.set",
  params: {
    terminal_session_id: $session,
    owner_id: "agent-status",
    ttl_seconds: 120,
    asset: {
      frames: [{
        svg: "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 16 16\"><circle cx=\"8\" cy=\"8\" r=\"6\" fill=\"#d77757\"/></svg>",
        duration_ms: 100
      }],
      loop: false,
      static_frame: 0
    }
  }
}' | curl --silent --show-error --fail-with-body \
  "$CC_KIT_TERMINAL_ICON_URL" \
  -H "Authorization: Bearer $CC_KIT_TERMINAL_ICON_TOKEN" \
  -H 'Content-Type: application/json' --data-binary @-
```

Example response:

```json
{"id":"set-1","result":{"lease_token":"<opaque-token>","revision":1,"persistent":false,"ttl_seconds":120}}
```

Store `result.lease_token` and `result.revision`. The actual revision is server-assigned, not necessarily 1. A successful full replacement or heartbeat returns a newer revision; clients must replace their stored revision. Requests from different owners cannot silently take over a live lease.

## Animation

Replace `asset` with multiple frames. This example alternates two colors every 250 ms:

```json
{
  "frames": [
    {"svg":"<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 16 16\"><circle cx=\"8\" cy=\"8\" r=\"6\" fill=\"#d77757\"/></svg>","duration_ms":250},
    {"svg":"<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 16 16\"><circle cx=\"8\" cy=\"8\" r=\"6\" fill=\"#619c72\"/></svg>","duration_ms":250}
  ],
  "loop": true,
  "static_frame": 0
}
```

- Frames are loaded once. No per-frame HTTP traffic or terminal output is needed.
- `loop:true` repeats; `loop:false` plays once and holds the **last** frame.
- Optional `params.playback:"static"` displays `static_frame` instead; default is `"animated"`. `static_frame` is zero-based and defaults to 0. Static playback is explicit; automatic IDE-wide reduced-animation preference integration is not implemented.
- Animation follows visibility of the **tab header icon**, not visibility of the terminal body. A background tab with a visible header animates. Hidden headers stop scheduling refreshes and resume on repaint.
- The stable icon repaints its hosts rather than replacing the tab icon/layout every frame.
- Rendering is 16 logical pixels, with fixed 64×64 rasters for bounded HiDPI memory. Above combined 4× user/system scale, sharpness may be reduced. Fractional scaling and cross-monitor GUI acceptance still need live IDE testing.

## Update, renew and clear

While a lease exists, all mutations require matching `owner_id`, `lease_token`, and `revision`. Use **one outstanding mutation per lease**. Concurrent requests may receive a conflict; a heartbeat can invalidate an older in-flight render. There is no idempotent retry cache: if the first successful response is lost, reset through Settings to recover instead of assuming the lease was not created.

Update by sending a new `asset` together with lease fields. Heartbeat uses `surface.icon.set` **without** `asset` or `playback`:

```sh
# Fill these from the most recent successful result; do not invent the revision.
LEASE_TOKEN='<result.lease_token>'
REVISION='<result.revision>'
jq -n --arg session "$TERM_SESSION_ID" --arg token "$LEASE_TOKEN" \
  --argjson revision "$REVISION" '{
  id: "renew-1", method: "surface.icon.set",
  params: {terminal_session_id:$session, owner_id:"agent-status",
    lease_token:$token, revision:$revision, ttl_seconds:120}
}' | curl --silent --show-error --fail-with-body "$CC_KIT_TERMINAL_ICON_URL" \
  -H "Authorization: Bearer $CC_KIT_TERMINAL_ICON_TOKEN" \
  -H 'Content-Type: application/json' --data-binary @-
```

Use its **new returned revision** for the next update or clear:

```sh
jq -n --arg session "$TERM_SESSION_ID" --arg token "$LEASE_TOKEN" \
  --argjson revision "$REVISION" '{
  id: "clear-1", method: "surface.icon.clear",
  params: {terminal_session_id:$session, owner_id:"agent-status",
    lease_token:$token, revision:$revision}
}' | curl --silent --show-error --fail-with-body "$CC_KIT_TERMINAL_ICON_URL" \
  -H "Authorization: Bearer $CC_KIT_TERMINAL_ICON_TOKEN" \
  -H 'Content-Type: application/json' --data-binary @-
```

Clear returns `{"id":"clear-1","result":{"cleared":true}}`. Clear rejects asset, playback, and TTL fields. On expiry/clear/reset, the previous icon and show-icon flag are restored without changing the title. Later native icon changes are retained as the fallback. Closed tabs release frame memory/timers; moving a terminal out of the supported tool window relinquishes its override.

`ttl_seconds` is an integer 1–86400. A first set without TTL is persistent until cleared or its tab/API lifetime ends. Omission on subsequent mutations inherits the lease's current TTL. Successful commit starts/renews the monotonic deadline; failed validation/rendering does not claim a tab or change its icon. Expiry cleanup occurs approximately once per second and is also checked before mutations.

## Supported SVG subset and limits

This is **not an unrestricted SVG viewer**. Unsupported features fail rather than being silently removed. Start with the examples above and validate actual intended artwork.

- 1–64 frames, UTF-8 SVG ≤16 KiB/frame and ≤512 KiB combined.
- Integer frame durations 50–5000 ms; sum ≤30 seconds.
- JSON request body ≤1 MiB, nesting ≤12, no duplicate/unknown fields or scalar coercion. Heavy JSON escaping can hit the wire limit before the decoded SVG limit.
- Static vector shapes/groups/paths, flat fill/stroke colors and bounded transformations; hardened XML parser and reconstructed SVG DOM.
- No DTD, processing instructions, external entities, scripts, event handlers, CSS/style, image, foreignObject, filters, SVG animation, `use`, `href`, `url()` or resource references (including gradients requiring references).
- Geometry, tree/path complexity, live frame memory (32 MiB), active decorated tabs (32), render concurrency (2) and queue (8) are bounded. The HTTP body bound protects plugin processing; the IDE's aggregator has already received the request before this handler sees it.

## Target and security boundaries

- Only started **local Classic/Reworked Terminal tool-window** sessions are supported. No remote development, SSH/WSL/container execution environments, or editor-area terminal tabs. The actual execution environment must be verifiably local; unavailable descriptor APIs fail closed.
- Session UUID routes the request; it is **not authentication**. The bearer authorizes this IDE instance; the lease token/revision protects against stale mutation, not against a malicious holder of the bearer.
- A split pane can share a single containing tab with another shell. Both target that tab; lease ownership prevents separate panes from overwriting each other's active lease.
- The API neither focuses nor creates tabs. Unknown/unready/unsupported sessions never fall back to the selected tab.
- Direct loopback only; browser Origin/forwarding headers are rejected, no CORS/query credentials, no unauthenticated discovery/listing endpoint. Requests close the connection after one response; pipelined requests close the channel.
- Plugin code does not log credentials or request/renderer exceptions. **IDE BuiltInServer DEBUG logging can record `Authorization` before plugin authentication.** Avoid `org.jetbrains.io.BuiltInServer` DEBUG with this API enabled; after diagnostics, disable DEBUG and reset credentials. Clipboard contents and copied shell commands are secrets—do not paste them into logs, tickets or shared history.
- This borrows cmux's `surface.icon.set/clear` and frame fields, but is **not wire-compatible** with its workspace/surface UUID or PID/TTY authorization protocol.

## Errors and compatibility

Errors are `{"id":<id-or-null>,"error":{"code":"...","message":"..."}}`. Examples: `invalid_request`, `invalid_asset`, `unauthorized`, `forbidden`, `session_not_found`, `session_not_ready`, `ambiguous_session`, `unsupported_terminal`, `unsupported`, `lease_conflict`, `revision_conflict`, `resource_limit`, `busy`, `disabled`, `cancelled`, `render_failed`, `internal_error`. Validation failures leave the existing icon untouched. The platform can reject requests before dispatch (for example Origin checks), producing its own 404 rather than an RPC envelope.

Compilation/testing currently uses the installed **263 SDK**. The plugin's minimum build remains **253.33813.25**, but loading the main plugin on 253 and supporting the new icon API on 253 are **separate, unverified gates**. Reworked/renderer APIs are evolving/internal; unavailable execution-descriptor APIs return unsupported rather than guessing. `make test` is not a replacement for live IDE, Netty dispatch, optional-plugin, scale, and minimum-SDK acceptance.

## Developer verification

```sh
make test   # Kotlin protocol/lease/XML/animation timing tests, separate out-test/
make        # production compile and claude-commit-plugin.jar
```

Use the IDE's bundled runtime for tests: the installed 263 SDK requires its Java 25 runtime even though plugin classes target JVM 21. Install/restart only in a test IDE with user consent. Verify both engines, multiple same-name/background tabs and projects, clear/TTL/reset, header animation, scaled displays, remote/editor refusal, Terminal plugin disabled, and close/unload cleanup. No real IDE installation/restart is part of these build commands.
