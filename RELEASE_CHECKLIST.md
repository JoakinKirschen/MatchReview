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
