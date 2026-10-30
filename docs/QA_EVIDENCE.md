# QA Evidence - 0.3.0

The Android dashboard includes **QA Evidence** next to **Activity** at widths of
640 dp or more. Narrow screens stack the two cards to keep their contents usable.
No sandbox path copying, ADB, broad storage permission or cloud account is needed.
Refresh the card after collecting evidence; it also refreshes on returning to Tester.

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
