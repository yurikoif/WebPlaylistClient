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
- Lets TV users search the website from inside the app.
- Lets TV users enter a supported series URL directly and jump to it.
- Shows matching series thread links as selectable results.
- Resolves episode player pages into direct playable media URLs where possible.
- Plays video with AndroidX Media3 / ExoPlayer.
- Auto-advances when an episode ends by default.
- Offers a playback mode toggle to switch from next episode to looping the current episode.
- Saves the last selected series, episode index, and playback position locally.

## TV Interaction Model

The app is intentionally full-screen-first:

- The video player fills the entire TV screen.
- Pressing remote OK/Select while watching pauses playback, opens floating controls, and focuses play/pause.
- Pressing Up while watching also pauses playback and opens the floating controls.
- Selecting an episode hides the overlay and returns to full-screen video.
- The URL/search panel floats above the transport controls.
- Previous, play/pause, and next float in the center of the screen with YouTube-style transport symbols.
- The playlist floats at the bottom as a horizontal episode rail.
- From the transport controls, Up focuses the URL panel and Down focuses the current episode in the playlist rail.
- Back hides the overlay when video is already loaded.

Supported direct URLs currently match the Discuz thread format:

```text
https://myself-bbs.com/thread-44169-1-1.html
thread-44169-1-1.html
```

The app does not attempt to bypass DRM, login, payment, captcha, or other access controls.

## Website Search

The app mirrors the website header search form. It first loads:

```text
https://myself-bbs.com/portal.php
```

Then it submits a title search to:

```text
https://myself-bbs.com/search.php?searchsubmit=yes
```

Important form fields:

```text
mod=forum
srchtype=title
srhfid=0
srhlocality=portal::index
srchtxt=<query>
searchsubmit=true
formhash=<value from portal page, when present>
```

Search results are parsed by collecting unique `thread-...html` links from the returned page.

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

The in-app search can also select another series at runtime, and the selected URL is remembered in `SharedPreferences`.

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
