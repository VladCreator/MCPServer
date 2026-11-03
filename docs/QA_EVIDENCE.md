# QA Evidence - 0.4.0

Android can send a linked flow's saved evidence to the explicitly paired
**Tester Desktop** inbox over encrypted USB or Wi-Fi. Select **Desktop** to pair
and check availability, then **Send** beside a flow and confirm its destination
and file count. All dates in that flow are included; originals are preserved.
See [pairing, limits, and receiver setup](TESTER_DESKTOP.md#send-qa-evidence-to-desktop).

The Android dashboard includes **QA Evidence** next to **Activity** at widths of
640 dp or more. Narrow screens stack the two cards to keep their contents usable.
Activity entries are grouped by calendar day in the device's time zone, newest
first, with up to **1000** recent entries retained for display and after restart.
Date headers show entry counts and can collapse or expand a day's activities;
Activity days start expanded. Every row displays its **Flow #N** or **Unlinked**
label beside the tool name, so activities from the same flow can be followed across dates. Tied
timestamps are ordered by descending activity ID. Individual rows expand to show
arguments and results.
No sandbox path copying, ADB, broad storage permission or cloud account is needed.
The card refreshes after QA sessions, screenshots, photos, baselines and run evidence
tools, and when returning to Tester; use Refresh for files saved by another process.
Evidence files remain grouped by calendar day in the device's time zone, newest first.
All days start collapsed: tap a date to expand or collapse its complete file list.
Each date shows its file count; all dates are available in the scrollable list.
Within each date, **Flow #N** and **Unlinked** headers also collapse or expand
their file lists independently; flows start collapsed. Tap a filename to view
its content. Share and Download icon buttons sit beside each filename with
accessible labels; the source and local time appear underneath the name.
Compact rows include file-type icons for images, videos,
PDFs, structured data and text reports.

## Flow correlation

On Android, each new `qa_session begin` and each admitted native `run_route`
receives a stable local **Flow #N**. IDs are per test/run, not per connection:
one agent can have many flows. Retrying a deduplicated native submission keeps
its ID; a route inside a manual QA session gets a separate run ID.

Connected agents show the IDs they originated. Each Activity row and QA Evidence
item displays the same flow ID. Each agent has its own manual QA session; its tool
calls between begin/end belong to that session.
Native run operations use their explicitly owned run instead, including async
runs and later artifact exports. Run steps remain in the native run timeline,
not duplicated as individual Activity calls.

Saved QA report pairs, cumulative step lists, approved screenshots, camera photos
and visual baselines created within a manual session are linked automatically.
Native reports and retained artifacts use the run's ID. Old records and files
without known provenance appear as **Unlinked**; Tester does not infer a link
from matching timestamps or filenames. Overwriting a baseline outside a session
removes its old link.
Connection/authentication and separate dashboard approval events also remain
Unlinked when they do not carry a verified test/run association.

Links survive process restart for retained history/evidence. Active manual QA
sessions are still in-memory and are not resumed after restart. Flow metadata
does not extend the existing retention of runs or Activity. Flow IDs are display
identifiers, not credentials: run-key checks, approvals and sharing confirmation
are unchanged. Client connection IDs stay in local metadata and are not added to
exported reports. The flow list stores correlation metadata (not report bodies);
it lasts until app data is cleared. IDs are local to that app installation.

Evidence icons have theme-aware colored backgrounds: images, video/audio, PDFs,
and text/data documents have distinct treatments.

## What appears

- Existing JSON/Markdown `qa_session` reports in private `files/qa/reports`.
- One cumulative TXT file per `qa_session`, in `files/qa/evidence`, updated after
  every recorded step (shown as `steps-1a2b3c4d.txt`). It lists actions in order,
  for example `1. Open Project`, `2. Tap Drawings`, `3. Open Drawing`, and records
  the flow, time, status, expected/actual result, notes, and screenshot outcome.
  The list survives an interruption without needing `action=end`; final
  JSON/Markdown reports remain available. Older per-step TXT files are preserved.
- Optional JPEG screen evidence for an individual step. Request it explicitly
  with `capture_screenshot=true`; Tester asks for approval for that screenshot
  and saves it beside the cumulative step list. Screenshots are never captured by default.
  If the accessibility screenshot service is unavailable or capture is blocked,
  the step trace and tool result report that failure rather than substituting a
  camera image.
- JPEGs explicitly requested using `take_screenshot` are now also saved in
  `files/qa/evidence`, not just returned inline to the agent. They are linked to
  the active session's flow and support filename previews, sharing and downloads.
  A storage failure is reported as an error; no screenshot is captured automatically.
- Retained native run timelines as Markdown, JSON and JUnit, plus existing run
  artifacts. The dashboard is local user access; remote MCP run-key and sensitive
  tool approval checks are unchanged. Timelines are snapshots at refresh time.
- Saved files directly inside `files/qa/evidence` and `files/qa/baselines`.
- Camera photos in the app's external Pictures folder, including legacy
  `mcp_<timestamp>.jpg` photos in private files, and media in external Movies.

Supported extensions: Markdown, JSON, XML, TXT, LOG, JPEG, PNG, WebP, MP4, WebM
and PDF. Only these specific evidence locations are browsed, not arbitrary app
files, preferences, TLS keys, recovery journals or databases. Symbolic links
outside an approved directory are rejected.

Images returned only by other observation/assertion tools and host-side gateway/CI
reports are not automatically saved to the phone. Previously returned inline-only
screenshots cannot be recovered. This adds **no recording or new capture permission**; videos
appear only if already saved in a supported evidence folder. Retained native
runs/artifacts still follow their existing retention and quota limits.

Names shown in QA Evidence and used for staged sharing are compact and unique
within the evidence source (for example `r-<12 hex>.md` for native reports,
`p-<12 hex>.jpg` for legacy camera photos, `steps-<8 hex>.txt` for session
lists, and `s01-<8 hex>.jpg` for step screenshots). Original file paths and validation are unchanged.

## Viewing, sharing and downloading

**Tapping a filename** opens a full-screen preview with a Close action (system
Back also closes it). JSON is pretty-printed with indentation; invalid or truncated
JSON remains readable as original text with an explicit formatting warning.
Markdown renders headings, emphasis, lists, tables, blockquotes and code blocks.
Embedded HTML is escaped; scripts, external resources and link navigation are
blocked. Other text stays selectable monospace text, and images use a sampled
preview inside Tester. Text previews show up to 256 KiB with
an explicit truncation notice; sharing preserves the full file. PDF and video
files open in an installed Android viewer after confirmation. There is no separate View button.

The **Share icon** opens Android's Sharesheet after confirmation, where the user can choose Drive, mail or
another installed app. Tester does not upload anything itself. External opening
and sharing require explicit confirmation because legacy QA notes and media may
contain unredacted sensitive content.

The **Download icon** opens Android's system save picker with the evidence's
short filename and MIME type. Choose Downloads or another document location to
save the complete file, not just its preview. Cancelling leaves the original
evidence unchanged. Copy failures are shown in the card; a failed destination
may contain an incomplete file. The picker supplies access only to the selected
destination, so no broad storage permission is needed. Saved copies may contain
sensitive QA content and are outside Tester's private storage.

Only a staged copy is exposed through a non-exported FileProvider with temporary
read permission for the chosen app. The provider cannot expose original app
folders or run databases. Copies are limited to 256 MiB per file; expired copies
are removed on the next evidence refresh after 24 hours. Internal preview copies
are deleted immediately after decoding. Delivered/downloaded copies cannot be
recalled. There is no automatic cloud sync or evidence deletion action.

The evidence browser currently targets Android. iOS displays an explicit
availability notice rather than claiming unsupported local browsing.
