# Tester YAML routes (Android, schema v1)

`run_route` accepts an inline YAML document and optional typed parameters. Tester
validates the entire document before requesting approval or executing any action,
then executes the expanded steps locally. This is a small Tester-specific language,
inspired by Maestro; Maestro flow files are not compatible.

The initial implementation supports inline routes, not a stored route catalog.
Keep reusable YAML in your repository and send its contents through MCP.
[`open_then_back.yaml`](../tools/routes/open_then_back.yaml) is a parameterized
example; obtain actual package/resource IDs from the target app before running it.

## Example

```yaml
schema_version: 1
id: navigation.open_then_back
platform: android
app_id: com.example.projects
timeout_ms: 15000

parameters:
  pause_ms:
    type: integer
    default: 100

steps:
  - launch_app: {}
  - wait:
      condition: idle
      timeout_ms: 5000
  - tap:
      resource_id: open_project
  - delay:
      milliseconds: "${pause_ms}"
  - back: {}
  - assert:
      condition: visible
      resource_id: project_list
```

Invoke it using `tools/call` with:

```json
{
  "name": "run_route",
  "arguments": {
    "yaml": "<complete YAML file contents>",
    "parameters": {"pause_ms": 150}
  }
}
```

Through the QA Gateway, use the backend-prefixed name, usually
`android__run_route`. The gateway discovers it from the Android tool catalog;
iOS does not advertise this tool. No gateway-side YAML parser is needed.

## Document and parameter rules

Required fields: `schema_version: 1`, `id`, `platform: android`, `app_id`, `steps`.
Optional fields: `parameters`, `timeout_ms` (default/max 60000; minimum 1).
Route IDs and parameter names start with an English letter and contain only
letters, digits, underscores, dots or hyphens, with a maximum length of 80.
`app_id` must be a valid Android package name.

Parameters declare `type: string`, `integer` or `boolean` and an optional `default`.
Undeclared, missing and incorrectly typed parameters fail validation. References
must occupy an entire YAML scalar, such as `"${pause_ms}"`; substitution preserves
the parameter type and never evaluates scripts or reparses injected YAML.

Limits: 32768 YAML characters, 12 nesting levels, 20 parameters, 5000 characters
per text scalar and 50 steps. Unknown fields/commands, duplicate keys, explicit
tags, anchors and aliases are rejected. Only one YAML document is accepted. Quote
text that YAML might otherwise interpret as a boolean, number or date.

## Commands

Every step is a mapping with exactly one command and a mapping of its arguments.
Empty arguments use `{}`.

| Command | Arguments and behavior |
| --- | --- |
| `launch_app` | `{}`; launches `app_id` with idle waiting disabled. Add an explicit idle wait when needed. |
| `tap` | At least one of `resource_id`, `text`, `content_desc`, `class_name`; optional `text_exact`, `long_press` booleans. Requires exactly one visible match. |
| `input_text` | Required `text`; explicit field selector using `resource_id`, `content_desc` and/or `match_text`. Replaces text, supports an empty string, does not submit. Requires a unique visible match. |
| `scroll` | Required `direction: up/down/left/right`; optional `resource_id`, `text`, `content_desc`. A supplied selector must be unique; without one, uses the existing primary scrollable-area behavior. |
| `back` | `{}`; one Android Back action. |
| `delay` | Required integer `milliseconds`, 0..5000. No implicit idle wait before/after. |
| `wait` | Required `condition: visible/absent/text/idle`; selector except for idle; optional `expected` and `timeout_ms` (250..30000). Idle waits specifically for `app_id`. |
| `assert` | Required `condition: visible/absent/text/count`; selector; optional `expected` and `timeout_ms` (250..30000). Failure uses the existing assertion screenshot/tree evidence. |

Wait/assert selectors support `resource_id`, `text`, `content_desc`, `class_name`
and optional `text_exact`. `text` conditions check exact text using `expected` if
provided, otherwise the `text` selector. `count` requires a quoted integer
`expected` in 0..99, such as `"2"`; the native matcher caps results at 100.
Waits/assertions use the existing default timeout
(5000 ms) if omitted, bounded by the overall route deadline.

Indices, `nth`, container selectors, subroutes, loops, scripts, arbitrary tool
calls, connectivity changes and state mutations are intentionally unsupported.

## Execution and consent

- One approval covers the complete expanded route, including any assertion failure
  evidence. The dashboard shows scrollable expanded arguments, not just a truncated
  YAML preview. The existing confirmation setting still applies.
- A device-scoped lock prevents Tester UI/state operations from interleaving with
  a route, across clients. It cannot stop a person, the OS or another app from
  changing the screen.
- Before each non-launch action/check, require the target app in the foreground.
  An idle wait may wait for that app to appear after launch. Successful waits and
  assertions also recheck the foreground package before proceeding.
- Stop at the first failure, exception or deadline. Never retry a step automatically.
  The gateway does not replay `run_route` after session loss; the outcome may be
  unknown and should be inspected before manually retrying.
- The execution deadline begins after approval and lock acquisition. Cancellation
  propagates and releases the lock, but cannot undo a dispatched action. Deadlines
  are cooperative, not hard real-time guarantees for synchronous Android calls.
- There are no hidden idle waits between tap, delay and Back. Explicit waits before
  that timing window remain useful to establish a repeatable starting screen.

## Results, history and limitations

The MCP text result contains JSON with schema version, run ID, route ID, execution
location, status, wall-clock start time, monotonic duration and ordered step records.
Step statuses are `completed`, `failed`, `timed_out` or `skipped`. Executed steps
include tool output, start time and duration; delay steps also include the requested
delay. Assertion failure images are preserved in the MCP image block.

Timings measure tool execution, not exact render completion. Android scheduling and
gesture dispatch add jitter. A timeout does not establish that the current action
had no effect. If a client cancels/disconnects before receiving the result, there
is no durable run-status lookup in this first version.

Native command history omits route payloads and result content; gateway reports
omit route arguments and record success/failure and overall call duration. The
calling client receives step outputs and assertion evidence, which may contain
sensitive UI data; use non-sensitive test accounts. This version does not provide
screenshot masking or general failure-bundle redaction.

Container-scoped/stale-reference-safe targeting, combined waits, route storage,
build provenance and state restoration remain separate roadmap milestones in
[`QA_RELIABILITY_PLAN.md`](QA_RELIABILITY_PLAN.md).
