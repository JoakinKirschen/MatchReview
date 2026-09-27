# Pass 7 — Goals, assists, score correction, timeline and undo

## Starting point

This pass was built from the Pass 6 project revision.

## Implemented

- Goal action from an on-pitch player's action sheet.
- Quick “Our goal” flow with scorer and optional assist selection.
- Opponent-goal confirmation.
- Atomic event insertion and score refresh in Room transactions.
- Score values are derived from persisted `OUR_GOAL` and `OPPONENT_GOAL` events.
- Manual score correction adds or removes timeline goal events, keeping both views consistent.
- Undo removes the latest scoring event and immediately refreshes the score.
- Match/timeline navigation inspired by the supplied mobile screenshot.
- Reverse-chronological live timeline for goals, substitutions, player entries,
  injuries, dismissals and removals.
- Own-goal event editing:
  - change scorer;
  - change or remove assist;
  - move the event time by one-minute steps;
  - delete the event.
- Opponent-goal deletion from the timeline.
- Unknown-scorer and no-assist support.
- Unit tests for score counting, attribution validation and correction deltas.

## Data model

No Room schema migration was required. Pass 7 uses the existing `events` table fields:

- `type` identifies our or the opponent's goal;
- `playerId` stores the scorer;
- `relatedPlayerId` stores the assisting player;
- `timestampMs` stores logical match time;
- `periodNumber` stores the active period.

## Deferred to Pass 8

- CameraX preview and recording.
- Camera and microphone permissions.
- Foreground segmented recording.
