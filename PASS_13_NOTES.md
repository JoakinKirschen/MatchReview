# Pass 13 notes

## New match setup

- Competition, match size, formation, period count and period duration are copied from
  the most recently created match.
- Formation is selected from a legal list for the chosen match size; free-text illegal
  formations can no longer be entered.
- The view model also rejects an unsupported size/formation combination.
- Successful pitch placement no longer shows the "snapped to slot" snackbar. Error and
  cancellation feedback remains available.

## PDF match summary

- The review screen now offers **Download match summary (PDF)**.
- PDFs are created fully offline with Android's built-in `PdfDocument`.
- The summary includes match details, score, setup, timeline, player participation,
  recording totals, review notes and a privacy notice.
- CSV export remains available for detailed machine-readable data.

## Verification

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```


## Current package additions

- Added **2-4-1** to the legal 8v8 formation list, with unit coverage for setup
  validation and generated pitch rows.
- Added an adaptive **Pitch Replay** launcher icon, a monochrome themed-icon layer,
  and two documented alternative icon concepts.
