# QA Evidence - 0.3.0

The Android dashboard includes **QA Evidence** next to **Activity** at widths of
640 dp or more. Narrow screens stack the two cards to keep their contents usable.
Activity also groups entries by local calendar day, newest first. Each date starts
collapsed and shows its activity count; tap it to expand or collapse that day's
entries. All retained activity dates are available in the scrollable list,
replacing the four-entry preview and Show all control. Individual activity rows
still expand separately to show arguments and results.
No sandbox path copying, ADB, broad storage permission or cloud account is needed.
Refresh the card after collecting evidence; it also refreshes on returning to Tester.
Evidence is grouped by calendar day in the device's time zone, newest first.
All days start collapsed: tap a date to expand or collapse its complete file list.
Each date shows its file count; all dates are available in the scrollable list.
View/Open and Share sit beside each filename; the source and local time appear
underneath the name. Compact rows include file-type icons for images, videos,
PDFs, structured data and text reports.

## Flow correlation

On Android, each new `qa_session begin` and each admitted native `run_route`
receives a stable local **Flow #N**. IDs are per test/run, not per connection:
one agent can have many flows. Retrying a deduplicated native submission keeps
its ID; a route inside a manual QA session gets a separate run ID.

Connected agents show the IDs they originated. Within each expanded day,
Activity and QA Evidence group records by the same flow ID. Each agent has its
own manual QA session; its tool calls between begin/end belong to that session.
Native run operations use their explicitly owned run instead, including async
runs and later artifact exports. Run steps remain in the native run timeline,
not duplicated as individual Activity calls.

Saved QA report pairs, camera photos and visual baselines created within a manual
session are linked automatically. Native reports and retained artifacts use the
run's ID. Inline images are still not automatically saved. Old records and files
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

Inline-only MCP screenshots and host-side gateway/CI reports are not automatically
saved to the phone. This release adds **no recording or new capture permission**;
videos appear only if already saved in a supported evidence folder. Retained
native runs/artifacts still follow their existing retention and quota limits.

## Viewing and sharing

**View** opens text reports as selectable plain text (no HTML/script execution),
or a sampled image preview inside Tester. Text previews show up to 256 KiB with
an explicit truncation notice; sharing preserves the full file. PDF and video
**Open** uses an installed Android viewer.

**Share** opens Android's Sharesheet, where the user can choose Drive, mail or
another installed app. Tester does not upload anything itself. External opening
and sharing require explicit confirmation because legacy QA notes and media may
contain unredacted sensitive content.

Only a staged copy is exposed through a non-exported FileProvider with temporary
read permission for the chosen app. The provider cannot expose original app
folders or run databases. Copies are limited to 256 MiB per file; expired copies
are removed on the next evidence refresh after 24 hours. Internal preview copies
are deleted immediately after decoding. Delivered/downloaded copies cannot be
recalled. There is no automatic cloud sync or evidence deletion action.

The evidence browser currently targets Android. iOS displays an explicit
availability notice rather than claiming unsupported local browsing.
