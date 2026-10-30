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
| `tap` | At least one of `resource_id`, `text`, `content_desc`, `class_name`, `within`; optional `text_exact`, `long_press`, `visibility`. Requires exactly one match. |
| `input_text` | Required `text`; explicit field selector using `resource_id`, `content_desc`, `match_text`, `class_name` and/or `within`; optional `text_exact`, `visibility`. Replaces text, supports an empty string, does not submit. Requires a unique match and editable target. |
| `scroll` | Required `direction: up/down/left/right`; optional selector including `within`, `class_name`, `text_exact`, `visibility`. A supplied selector must be unique; without one, uses the existing primary scrollable-area behavior. |
| `back` | `{}`; one Android Back action. |
| `delay` | Required integer `milliseconds`, 0..5000. No implicit idle wait before/after. |
| `wait` | Required `condition: visible/absent/text/idle`; selector except for idle; optional `expected` and `timeout_ms` (250..30000). Idle waits specifically for `app_id`. |
| `assert` | Required `condition: visible/absent/text/count`; selector; optional `expected` and `timeout_ms` (250..30000). Failure returns native predicate/tree evidence; privacy-sensitive routes suppress images. |

Wait/assert selectors support `resource_id`, `text`, `content_desc`, `class_name`
and optional `text_exact`, `within`, `visibility`. Since 0.1.6, `text` conditions use the same native predicate
as direct MCP calls: a unique candidate's actual text must equal `expected`
(case-sensitive), or satisfy the text selector when no expectation is supplied.
The YAML compiler does not rewrite text assertions into visibility checks.
`count` requires a quoted integer
`expected` in 0..99, such as `"2"`; the native matcher caps results at 100.
Waits/assertions use the existing default timeout
(5000 ms) if omitted, bounded by the overall route deadline.

Since 0.1.7, `within` scopes matches to an ancestor container, using the same native
semantics as standalone MCP calls. For example:

```yaml
- tap:
    resource_id: open
    within:
      resource_id: project_beta
```

Actions default to `visibility: fully_visible`; checks default to accessibility
visibility. These policies use reported geometry, not guaranteed overlay detection.

Indices, `nth`, ephemeral `element_ref`, subroutes, loops, scripts, arbitrary tool
calls, connectivity changes and state mutations are intentionally unsupported.

## Execution and consent

- One approval covers the complete expanded route and its privacy/evidence policy.
  The dashboard shows scrollable expanded arguments, not just a truncated
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

## Durable runs (0.2.0)

`mode` defaults to `sync`. To return immediately after acceptance, use:

```json
{
  "yaml": "<complete YAML file contents>",
  "mode": "async",
  "run_key": "<caller-generated URL-safe secret, 32-128 characters>",
  "submission_id": "navigation-attempt-001",
  "disconnect_policy": "continue_until_terminal",
  "queue_timeout_ms": 10000,
  "privacy_sensitive": true
}
```

Generate and retain the key before submission; never commit it. The same key and
submission ID recover the same retained run without approval or execution again.
A different immutable plan under that pair is rejected. Submission IDs have
1–80 URL-safe characters. Sync generates omitted IDs/keys, but that cannot recover
a lost initial response: supply both explicitly when retry safety matters.

Use `get_run`/`cancel_run` with `run_id` and `run_key`. A new approved MCP session
can recover the original run only with that bearer key. Accepted execution
continues after transport disconnect; stopping Tester cancels active jobs.
Process restart marks unfinished runs `interrupted`, never resumes them, and marks
dispatched steps as outcome-unknown and remaining steps skipped. Inspect the target
before submitting a new attempt; this is deduplication, not exactly-once execution.

Queue waiting has a separate 250–30000 ms limit (default 10000); the route deadline
starts after lock acquisition. Cancellation is cooperative and cannot undo actions.
Recovery/deduplication lasts only while a run is retained: at most 64 runs, 8
active/pending; expired terminal runs are pruned on admission after 7 days.

## Results, history and limitations

The MCP text result contains JSON with schema version, run ID, route ID, execution
location, status, wall-clock start time, monotonic duration and ordered step records.
Durable steps include `pending`, `dispatched`, `completed`, `failed`, `timed_out`,
`interrupted` or `skipped`. Synchronous responses include live tool outputs and
timings; persisted/get/export results retain redacted statuses/timings, not free
text or parameter values. Dispatch is persisted before side effects.

Timings measure tool execution, not exact render completion. Android scheduling and
gesture dispatch add jitter. A timeout does not establish that the current action
had no effect. Use the original run ID/key after a lost response rather than replay.

Native command history omits route payloads and result content; gateway reports
omit route arguments and record success/failure and overall call duration. The
calling client receives approved live UI text, which may contain sensitive data.
`privacy_sensitive` defaults to true: assertion images and bundle screenshots are
suppressed. There is no reliable image masking. Unmasked bundle capture requires
`privacy_sensitive:false` at admission, explicit `include_screenshot:true` and
sensitive-tool consent. Automatic failure bundles remain metadata-only.
Non-sensitive routes also permit unmasked assertion images in the synchronous
response; that policy is shown during expanded-route approval.
See [P2](P2_0.2.0.md) for evidence and artifact policies.

Container scoping and standalone session-bound references are implemented in 0.1.7;
saved routes intentionally use stable selectors, not transient references.
Standalone combined waits are available through `wait_for_state`; no YAML extension
is required. Saved route catalogs, build provenance and owned state restoration
remain later milestones in [`AGENT_QUALITY_IMPLEMENTATION_PLAN.md`](AGENT_QUALITY_IMPLEMENTATION_PLAN.md).
