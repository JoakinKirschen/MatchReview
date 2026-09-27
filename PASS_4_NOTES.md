# Pass 4 notes — Drag-and-drop lineup editing

## Starting point
This pass was built from the latest Pass 3 working project:
`Historical source:` the Pass 3 project revision.

## Added
- Long-press drag gestures for players on the pitch and substitutes on the bench.
- Bench-to-pitch, pitch-to-bench and pitch-to-pitch movement.
- A lifted player ghost that follows the finger across the lineup editor.
- Highlighted pitch and bench drop targets.
- Automatic snapping to the nearest unoccupied formation slot.
- A highlighted formation target before the player is released.
- Free placement when the player is not close to a formation slot.
- Marker-safe coordinate clamping at the edges of the pitch.
- Persistence only after a valid drop, avoiding database writes for every pointer movement.
- Full-pitch validation when a substitute is dragged onto the field.
- Haptic feedback when a drag begins and when a drop is accepted or rejected.
- Snackbar confirmation for saved, snapped, benched, full-pitch and cancelled drops.
- Existing tap-based placement and accessibility movement actions remain available.
- Pure drag/drop rule tests for normalization, snapping, occupied slots and edge constraints.

## Gesture
1. Long-press a player.
2. Drag the lifted marker.
3. Release over the pitch to place the player.
4. Release near a free formation circle to snap into that role.
5. Release over the bench to remove an on-pitch player from the starting lineup.
6. Release elsewhere to cancel without changing the saved position.

## Pass boundary
Pass 5 adds the live match screen, kickoff, durable clock, pause/resume and period transitions.

## Verification
Run:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

Or push the complete project to GitHub and run the included build workflow.
