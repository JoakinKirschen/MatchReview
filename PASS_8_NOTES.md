# Pass 8 — CameraX recording

## Starting point

This pass was built from the Pass 7 project revision.

## Implemented

- Camera tab in the live match interface, based on the supplied mobile reference.
- CameraX preview using `PreviewView`.
- Back/front camera selection.
- Torch control for the back camera.
- Runtime camera permission handling.
- Optional microphone audio with separate runtime permission handling.
- Android 13 notification permission request.
- Foreground camera/microphone service for recording outside the visible camera tab.
- Persistent foreground notification with a stop-and-save action.
- HD H.264/MP4 recording through CameraX `Recorder`.
- Videos saved to the system MediaStore under `Movies/MatchReview`.
- Every start/stop creates a separate persisted recording segment.
- Segment metadata includes:
  - match-clock start and end;
  - CameraX recording duration;
  - content URI;
  - orientation;
  - audio setting;
  - bytes recorded;
  - completion, interruption, or failure status.
- Interrupted open segments are marked on service recovery.
- Live recording timer and match-clock overlay.
- Segment list with recording status and errors.
- Unit tests for recording-state and segment-time rules.

## Android behavior

The recording service declares the `camera|microphone` foreground-service types. Recording
must be started while MatchReview is visible, as required by modern Android versions. Once
started, the foreground notification keeps the recording session active when switching tabs
or briefly leaving the app.

## Deferred to Pass 9

- Mapping each match event to the active video segment and recording offset.
- Camera-mode goal/substitution shortcuts over the preview.
- Review playback that jumps directly to the correct recorded segment.
- Clip-oriented event navigation and thumbnails.
