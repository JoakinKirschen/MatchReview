# MatchReview release checklist

## Automated

- [ ] `./gradlew testDebugUnitTest assembleDebug`
- [ ] `./gradlew connectedDebugAndroidTest` on API 26 and API 35
- [ ] Confirm Room migration 1→2→3→4 with a copy of production data
- [ ] Install the release candidate over the previous APK without clearing app data
- [ ] Confirm app `versionCode` and `versionName` increased for the release

## Match workflow

- [ ] Create, edit, and remove a test team/player; verify every removal asks for confirmation
- [ ] Create matches using 3v3, 5v5, 8v8, and 11v11 presets
- [ ] Confirm every offered formation matches the selected team size
- [ ] Confirm **2-4-1** is available for 8v8 and creates GK + 2 DEF + 4 MID + 1 FWD
- [ ] Verify previous-match competition, size, formation, and period defaults
- [ ] Create squad and lineup
- [ ] Long-press and drag players bench→pitch, pitch→pitch, and pitch→bench
- [ ] Verify tap-based and accessibility-safe lineup placement
- [ ] Kick off, pause, resume, end every configured period, and finish
- [ ] Verify clock recovery after activity recreation and process restart
- [ ] Verify rolling and non-rolling substitutions
- [ ] Verify goals, score correction, undo, and timeline editing
- [ ] Verify player minutes after multiple appearances

## Camera and storage

- [ ] Deny camera permission and recover from Settings
- [ ] Record with microphone granted and denied
- [ ] Switch front/rear camera and use the torch
- [ ] Lock the screen, background the app, receive a call, and return
- [ ] Force-stop during recording and verify the segment is marked interrupted
- [ ] Verify warning below 1 GB and recording block below 250 MB
- [ ] Play timeline-linked clips and previous/next events

## Backup and restore

- [ ] Create a password-encrypted `.mrbak` backup without videos
- [ ] Save through local storage and at least one installed cloud document provider
- [ ] Restore onto a clean installation and compare teams, players, matches, events, and minutes
- [ ] Confirm a wrong password and a damaged file leave existing data unchanged
- [ ] Confirm restore requires destructive-action confirmation
- [ ] Confirm restore is blocked during an active match or recording
- [ ] Create an opt-in backup with videos and verify restored clips play
- [ ] Confirm unavailable media is reported as a warning rather than silently omitted
- [ ] Confirm the password is recorded securely outside the backup; there is no password recovery

## Privacy, export, and deletion

- [ ] Export CSV and open it in a spreadsheet application
- [ ] Export the PDF match summary and verify details, timeline, minutes, recordings,
      review notes, and privacy notice
- [ ] Confirm player names, events, minutes, and recording references are correct
- [ ] Delete a match and verify its app-recorded MediaStore clips are removed
- [ ] Confirm an imported source video remains on the device
- [ ] Confirm no personal data is included in logs or crash reports
- [ ] Confirm the club/team has an appropriate retention and sharing policy

## Accessibility and usability

- [ ] TalkBack: navigate dashboard, lineup, live controls, camera, timeline, export, and deletion
- [ ] Test 200% font size and display size
- [ ] Test portrait and landscape layouts
- [ ] Verify colour is not the only indicator of state
- [ ] Verify every destructive action requires confirmation
- [ ] Check the launcher icon with round, squircle, and Android themed-icon settings
- [ ] Confirm launcher-icon pitch lines and play symbol remain legible at small sizes

## Usability passes 26–35

- [ ] Complete the match-day readiness check and follow each warning back to lineup editing
- [ ] Swap bench↔pitch and pitch↔pitch players; verify both positions and states persist
- [ ] Verify Summary, Timeline, Video, and Data review sections retain their state
- [ ] Filter squad members by availability and confirm unavailable players cannot be selected
- [ ] Search matches by opponent, team, venue, and competition
- [ ] Select a match date with the date picker and verify the stored `yyyy-MM-dd` value
- [ ] Compare backup estimates with videos enabled and disabled
- [ ] Test imported video from a provider that grants and refuses persistable URI access
- [ ] Deny camera permission permanently and verify **Open app settings** recovery
- [ ] Test bottom navigation and Back icon with TalkBack

## Reliability passes 36–45

- [ ] Review a match with an intentionally inconsistent score and confirm the integrity warning appears
- [ ] Rapidly repeat goal, clock, and substitution controls; confirm duplicate commands are suppressed
- [ ] Change wall-clock time during an active clock test and confirm the recovery warning appears
- [ ] Open a match containing stale or invalid recording metadata and confirm the recording warning appears
- [ ] Create a format-2 backup, inspect it, restore it, and confirm a modified digest is rejected
- [ ] Restore a known-good format-1 backup to verify backward compatibility
- [ ] Export privacy-safe CSV and confirm names, notes, and media URIs are absent
- [ ] With TalkBack, confirm score changes are announced without announcements every second
- [ ] Confirm recording is blocked when the 512 MiB safety reserve would be crossed
- [ ] Create a practice match and verify its eight-player squad and 2-4-1 setup
- [ ] Confirm the dashboard season summary excludes draft and active matches


## Landscape, video and languages (passes 65–67)

### Landscape and tablets
- [ ] Turn the phone during a live match on every tab: the camera preview, open sheets and the chosen tab stay
- [ ] Turn the phone while recording: the recording continues; the next clip uses the new orientation
- [ ] Landscape: lineup, substitution mode and live pitch lie sideways; drag-and-drop lands where the finger is
- [ ] Tablet (10"): camera beside the pitch in landscape, above it upright; no camera tab
- [ ] Outdoor theme: bigger buttons and clock; labels readable in sunlight

### Recording
- [ ] Android 8 or 9: the storage permission is asked with the camera and the clip appears in Movies/MatchReview
- [ ] Stop filming "at period end": ending a period saves the clip; starting the next period records again with the same sound and camera
- [ ] Stop filming "when time is up": the clip stops at the planned time with the screen off, and restarts with the next period
- [ ] Start the next period while the previous clip is still saving: recording still restarts
- [ ] Start recording yourself during the break: it is not interrupted when the period starts
- [ ] Mark button while filming: the moment appears in the timeline and the full match video, not in the PDF

### Notification
- [ ] Lock the phone during a period: Our goal, Their goal and Pause/Resume work from the lock screen with a short vibration
- [ ] A double tap counts once; notification goals show "scorer not assigned" in the app

### Video review
- [ ] Watch full match: clips play back to back, the match clock is right, period and event jumps land a few seconds early
- [ ] Slow motion ¼× and ½×, frame steps back and forward, pinch zoom and drag, tap the zoom label to reset
- [ ] Save the full match as one video, a player reel and a marked-moments reel; play and share each

### Backups
- [ ] Automatic backup with videos shows the size warning
- [ ] Share latest backup sends the newest automatic backup to Drive or e-mail and it restores

### Languages
- [ ] Switch through all seven languages on the home screen; screens, notifications and a PDF change immediately
- [ ] Android 13+: Settings > Apps > MatchReview > Language lists the seven languages and matches the in-app menu both ways
- [ ] Phone in an unsupported language with "System": the app shows English
- [ ] Have a native speaker read the live screen, camera tab and review screen in each language you ship

## Release signing

- [ ] Build with your own release key (`RELEASE_SIGNING.md`), never with `app/debug.keystore`
- [ ] `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk` shows your certificate
- [ ] Key and passwords are stored in two safe places
