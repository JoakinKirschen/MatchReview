# Pass 5 — Live match clock and period controls

Started from the Pass 4 project revision.

## Implemented

- Dedicated immersive live match screen based on the supplied match-day reference.
- Red scoreboard card with score, cumulative match clock, period number, and period clock.
- Kick-off, pause, resume, end-period, next-period, and finish-match controls.
- Persistent Room-backed match state transitions.
- Clock segments based on `SystemClock.elapsedRealtime()` rather than a database counter.
- Wall-clock fallback after a device reboot, capped to avoid adding an implausibly long interval.
- Initial player participation intervals at kick-off and each new period.
- Live player-minute badges derived from participation intervals.
- Bench minute cards and player information sheets.
- Period records and automatic closing of participation intervals at period end.
- Active-match resume card on the dashboard.
- Match state remains recoverable when leaving the screen or after Activity/process recreation.
- Screen stays awake during match-day states.
- Confirmation before leaving or finishing a match.
- Additional clock calculation tests.

## Deliberately deferred

- Substitution transactions and moving players during the live match: Pass 6.
- Goal, assist, score, and undo actions: Pass 7.
- CameraX recording: Pass 8.
- Final recovery reconciliation UI for ambiguous device-clock changes: Pass 10.

## Important behavior

Leaving the live screen does not stop a running logical match clock. The dashboard displays a **Resume live match** card. Pausing and period transitions are committed to Room immediately.
