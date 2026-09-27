# Pass 6 — Live substitutions and player removal

## Starting point

This pass was built from the Pass 5 project revision.

## Implemented

- Live substitutions from either an on-pitch player or a bench player.
- Atomic substitution transactions in Room.
- Outgoing participation intervals close at the exact logical match time.
- Incoming participation intervals open at the same logical match time.
- Pitch coordinates, tactical role, and formation slot transfer to the incoming player.
- Rolling-substitution behavior:
  - enabled: outgoing players return to the bench and may re-enter;
  - disabled: outgoing players become removed and cannot re-enter.
- Removal without replacement for:
  - normal bench removal;
  - injury;
  - dismissal.
- Ability to add an eligible bench player without replacement when the team is below
  the configured number of players on the pitch.
- Substitution, player-on, injury, dismissal, and player-off timeline events.
- Match-day squad operational states synchronized at kickoff and new periods.
- Live bench cards show minutes played and whether a player cannot return.
- Unit tests for eligibility, pitch capacity, dismissal, and rolling-substitution rules.

## Deferred to Pass 7

- Goals and assists.
- Score updates and corrections.
- Undo of recent match actions.
- Event editing from the live timeline.
