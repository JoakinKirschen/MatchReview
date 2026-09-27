# Pass 9 — Video/event integration

## Starting point

This pass was built from the Pass 8 project revision.

## Implemented

- Match events are linked to the active recording segment when they are created.
- Precise video offsets use wall-clock recording/event timestamps for new recordings.
- A Room 2→3 migration adds the recording start epoch without deleting existing data.
- Existing recordings and events are backfilled using logical match time when wall-clock
  metadata is unavailable.
- Goal corrections remap the event to the appropriate clip.
- Recording finalization retries mapping for events created while a clip was being prepared.
- Camera preview quick actions inspired by the supplied reference:
  - own-team goal;
  - substitution;
  - opponent goal.
- Timeline cards show clip availability and the event's offset in the video.
- Tapping **Clip** opens Media3 playback and seeks directly to the event.
- Playback supports previous/next mapped-event navigation, including events in other segments.
- Mapping and playability unit tests.

## Mapping behavior

For recordings created in Pass 9, the event's real occurrence time is compared with the
recording's persisted wall-clock start. This remains accurate while the match clock is paused.
For older Pass 8 clips, MatchReview falls back to the event and segment match-clock values.

## Deferred to Pass 10

- Storage-pressure warnings and recording deletion.
- Export/share bundles and privacy controls.
- Device/instrumentation migration tests.
- Broader accessibility and interruption hardening.
