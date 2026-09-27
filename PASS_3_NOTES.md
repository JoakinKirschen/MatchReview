# Pass 3 notes — Pitch lineup builder

## Starting point
This pass was built from the latest Pass 2 working project:
`Historical source:` the Pass 2 project revision.

## Added
- A dedicated `lineup/{matchId}` route after squad selection.
- Responsive green football pitch with markings inspired by the supplied reference.
- Starting-player markers with shirt number, name and match role.
- A horizontal substitutes bench containing the remaining selected players.
- Normalized pitch coordinates (`0.0..1.0`) persisted in the existing lineup table.
- Formation-slot generation for configured formations such as 4-3-3.
- Safe fallback layouts when the formation does not match the configured player count.
- One-tap automatic lineup placement.
- Player placement sheet for:
  - placing a substitute in the next free slot;
  - choosing goalkeeper, defence, midfield or forward slots;
  - moving an on-pitch player in four directions;
  - returning a player to the bench.
- Accessibility custom actions for directional player movement.
- Full-lineup validation and explicit confirmation for a smaller lineup.
- Match state changes to `LINEUP_READY` when the lineup is saved.
- Unit tests for formation layout generation.

## Pass boundary
Long-press drag-and-drop, slot snapping and haptic feedback remain Pass 4. Pass 3 intentionally provides tap-based and accessibility-safe placement first.

## Verification
Run:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

Or push the complete project to GitHub and run the included build workflow.
