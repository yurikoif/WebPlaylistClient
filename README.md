# Web Playlist

Android TV / Google TV client for turning a supported episode-list page into a remote-friendly playlist player.

The current site adapter targets Myself BBS Discuz pages such as:

```text
https://myself-bbs.com/thread-44169-1-1.html
```

## What It Does

- Uses a video-first TV design: playback owns the full screen, and every other interaction appears as a floating overlay.
- Loads the last selected series, or the bundled default sample URL.
- Parses episode rows from the series page.
- Lets TV users enter a supported series URL directly and jump to it.
- Resolves episode player pages into direct playable media URLs where possible.
- Plays video with AndroidX Media3 / ExoPlayer.
- Auto-advances when an episode ends by default.
- Offers a playback mode toggle to switch from next episode to looping the current episode.
- Saves the last selected series URL, episode index, and playback timestamp locally.

## TV Interaction Model

The app is intentionally full-screen-first:

- The video player fills the entire TV screen.
- Pressing remote OK/Select while watching pauses playback, opens floating controls, and focuses play/pause.
- Pressing directional keys while watching opens floating controls without pausing.
- Short-pressing Left/Right while watching opens floating controls focused on back/forward 10 seconds.
- Long-pressing Left/Right while watching seeks backward/forward by 30 seconds per repeat.
- Selecting an episode hides the overlay and returns to full-screen video.
- The URL panel floats above the transport controls.
- Previous episode, back 10 seconds, play/pause, forward 10 seconds, and next episode float in the center of the screen with drawn media icons.
- The playlist floats at the bottom as a horizontal episode rail.
- From the transport controls, Up focuses the URL panel and Down focuses the current episode in the playlist rail.
- Back hides the overlay when video is already loaded.

Supported direct URLs currently match the Discuz thread format:

```text
https://myself-bbs.com/thread-44169-1-1.html
thread-44169-1-1.html
```

Progress is saved periodically and when Android sends pause/stop lifecycle events, so reopening the app resumes the remembered series, episode, and timestamp.

The app does not attempt to bypass DRM, login, payment, captcha, or other access controls.

## Episode Parsing

The current parser is `MyselfBbsEpisodeListParser`.

Selectors:

```text
ul.main_list > li
li > a:first-child
li a[data-href]
```

Episode numbers are extracted from titles with:

```text
第\s*(\d+)\s*話
```

If every row has a parsed number, episodes are sorted by that number. Otherwise, DOM order is preserved.

## Updating The Default Series

Change `DEFAULT_SERIES_URL` in:

```text
app/src/main/java/com/example/webplaylist/MainActivity.kt
```

The URL overlay can open another supported series at runtime, and the selected URL is remembered in `SharedPreferences`.

## Build

This checkout currently does not include a Gradle wrapper. With Gradle installed, run:

```bash
gradle assembleDebug
```

If a wrapper is added later, use:

```bash
./gradlew assembleDebug
```

## Install On Google TV

Enable developer options and USB/network debugging on the TV, then install:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## If The Website Changes

Update the parser classes under:

```text
app/src/main/java/com/example/webplaylist/parser/
```

For a changed episode page/player structure, update:

```text
app/src/main/java/com/example/webplaylist/resolver/
```
