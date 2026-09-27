# MatchReview implementation plan — 10 core passes + enhancements

## Pass 1 — Match-day data foundation (included in this package)
- Room database version 2 with migration from the original package.
- Typed match, availability, player, period, recording and participation states.
- Match-day tables for squad selection, pitch placement, periods, clock segments, player participation and recording segments.
- Match setup fields for periods, minutes per period, players on pitch and rolling substitutions.
- Pure clock/minutes calculation utility and unit tests.
- Existing teams, players, matches, review and imported-video features remain available.

## Pass 2 — Available-player selection (included in this package)
Added the match-day squad screen, availability states, search/filtering, bulk selection behavior and validation.

## Pass 3 — Pitch lineup builder (included in this package)
Added a responsive football pitch, substitutes bench, persisted normalized coordinates, automatic formation slots, tap-based positioning and accessibility movement actions.

## Pass 4 — Drag and drop (included in this package)
Added long-press dragging, a lifted drag preview, pitch/bench drop targets, free placement, unoccupied-slot snapping, final-position persistence and haptic feedback.

## Pass 5 — Live match clock
Add kickoff, pause/resume, period transitions, durable timing and active-match recovery.

## Pass 6 — Player minutes and substitutions
Open/close participation intervals, show live minutes and support rolling/non-return substitutions.

## Pass 7 — Player actions (included in this package)
Added goal/assist actions, opponent goals, an event-derived score, correction, timeline editing and undo.

## Pass 8 — CameraX recording (included in this package)
Added camera preview, runtime permissions, optional audio, camera controls, MediaStore output,
foreground operation and persisted segmented video recording.

## Pass 9 — Video/event integration (included in this package)
Mapped events to recording segments, added camera-mode quick controls, clip indicators, and review playback that seeks directly to each event.

## Pass 10 — Hardening and release (included in this package)
Added migration/device tests, orphan-recording recovery, storage warnings and blocking,
accessibility labels, CSV exports, permanent match/clip deletion, and privacy safeguards.


## Post-plan enhancements

### Pass 11 — Team/player management and match-size presets
Added confirmed team/player removal, player editing, simplified squad selection, and
legal 3v3, 5v5, 8v8, and 11v11 formation choices. The 8v8 options include **2-4-1**.

### Pass 12 — Lineup and live-screen layout reliability
Removed the lineup scrolling parent that competed with drag input, retained long-press
drag-and-drop and tap placement, and made the lineup and live pitches use available
screen space more efficiently.

### Pass 13 — Match defaults and PDF summaries
Added previous-match defaults, legal formation validation in both UI and view model,
and an offline printable PDF match summary alongside CSV export.

### Pass 14 — Encrypted backup and restore
Added password-encrypted, versioned `.mrbak` packages; complete Room-table export and
transactional restore; Android document-provider support; optional video inclusion;
active-match safeguards; package validation; and backup encryption tests.

## Verification
Run:

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Or push the package to GitHub and run the included **Build Android APK** workflow.


## Progress

All 10 core passes and enhancement passes 11–14 are complete.

- Pass 1: complete
- Pass 2: complete
- Pass 3: complete
- Pass 4: complete
- Pass 5: complete — live match clock, periods, recovery, and match-day UI
- Pass 6: complete — substitutions, removals, re-entry rules, and participation updates
- Pass 7: complete — goals, assists, score correction, timeline editing, and undo
- Pass 8: complete — CameraX foreground segmented recording
- Pass 9: complete — event/video mapping, camera quick actions, and clip playback
- Pass 10: complete — hardening, release checks, exports, deletion, accessibility, storage and privacy
- Pass 11: complete — team/player editing and removal, squad simplification, match-size presets
- Pass 12: complete — reliable long-press drag handling and space-optimised layouts
- Pass 13: complete — previous-match defaults, legal formations, and PDF summaries
- Pass 14: complete — encrypted, versioned backup/restore with optional media


## Pass 7 status

Completed: player goal actions, opponent goals, score consistency, correction, timeline editing, and undo.


## Pass 8 status

Completed: CameraX preview, permissions, optional audio, foreground service, MediaStore segments and recording recovery.


## Pass 9 status

Completed: precise event-to-video mapping, legacy backfill, camera quick actions, and event-based playback navigation.


## Pass 10 status

Completed: release hardening, recovery, storage protection, CSV export, deletion/privacy controls, and verification tests.
