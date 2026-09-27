# Pass 2 — Available-player selection

Started from the latest Pass 1 working project at:

`Historical source:` the preceding project revision.

## Added

- A match-day squad screen immediately after match creation.
- Automatic creation of match-specific squad records from the selected team roster.
- Availability states:
  - Available
  - Unavailable
  - Injured
  - Suspended
  - Not selected
  - Unknown
- Player search by name, shirt number or position.
- Filters for all, selected, available and unavailable players.
- Individual player selection with validation.
- “All available”, “Select available” and “Clear” bulk actions.
- Selected/available/required counters.
- Warning when fewer players are selected than the configured starting-lineup size.
- Ability to save a smaller squad rather than blocking the coach.
- A squad editing entry point from the match review screen.
- Unit tests for squad selection rules.

## Deliberately deferred

Pass 3 adds the actual pitch, bench and starting-lineup placement. Pass 4 adds drag and drop.
