# Pass 12 — reliable long-press drag-and-drop and space-optimised pitch

## Changes

- Removed the vertically scrolling parent from the lineup editor so it no longer
  competes with player drag gestures.
- Drag coordinates use the pointer's absolute position on every event instead of
  accumulated deltas.
- After a long press, players can be dragged:
  - from the bench onto the pitch;
  - between pitch positions;
  - from the pitch back to the bench.
- The pitch and bench are one continuous drag surface with highlighted drop feedback.
- Reworked the lineup screen to devote nearly all available height to the pitch.
- Reduced header, bench, spacing, and action-control sizes.
- Reworked the live match screen:
  - compact 50 dp header;
  - compact score action row;
  - pitch expands into all remaining space;
  - 64 dp substitutes strip;
  - compact tabs and match controls;
  - removed the large unused gaps above the system navigation area.
- Corrected live pitch marker positioning so stored coordinates represent marker centres.

Tapping a player still opens placement or substitution controls as a fallback and for
users who do not use drag gestures.
