# Android TV Web Playlist Client Plan

## Goal
Build an Android TV / Google TV APK that turns an old episode-list video website into a continuous playlist player.

The app should:
- Load a configured series page URL or bundled sample HTML.
- Parse episodes from the page.
- Use a video-first TV design where playback fills the screen and everything else appears as a floating overlay.
- Display episodes in correct order with TV remote navigation.
- Resolve selected episode pages/player URLs into playable media URLs where possible.
- Play with AndroidX Media3 / ExoPlayer.
- Auto-advance when playback ends.
- Let the user toggle end behavior between next episode and looping the current episode.
- Remember last watched episode and approximate playback position locally.
- Avoid bypassing DRM, login, payment, captcha, or anti-circumvention controls.

## Sample Page
Series page:

https://myself-bbs.com/thread-44169-1-1.html

Show detected from page title:

涼宮春日的憂鬱（2009）【全 28 集】

## DOM Findings
The page is a Discuz thread. The episode list is under `劇集列表`.

Episode-list pattern:

- Episode rows: `ul.main_list > li`
- Episode title: first direct child anchor, equivalent to `li > a:first-child`
- Player URL: nested anchor with `data-href`, equivalent to `li a[data-href]`

Example row:

    <ul class="main_list">
      <li>
        <a href="javascript:;">第 01 話 涼宮春日的憂鬱Ⅰ</a>
        <ul class="display_none">
          <li>
            <a href="#"
               data-href="https://v.myself-bbs.com/player/play/44169/001\r"
               target="_blank"
               class="various fancybox.iframe">站內</a>
          </li>
        </ul>
      </li>
    </ul>

The list includes episode player links from `001` through `028`.

## Parser Plan
Create a site adapter, e.g. `MyselfBbsEpisodeListParser`.

Data model:

    data class Episode(
        val title: String,
        val episodeNumber: Int?,
        val pageUrl: String,
        val mediaUrl: String? = null,
    )

Parsing rules:
- Fetch series page with OkHttp.
- Parse with JSoup.
- Select `ul.main_list > li`.
- Extract title from the first direct child anchor.
- Extract episode/player URL from nested `a[data-href]`.
- Trim whitespace and the observed trailing carriage return.
- Normalize relative URLs against the series page URL.
- Extract episode number from title with `第\s*(\d+)\s*話`.

Ordering:
- Primary: sort by parsed episode number.
- Fallback: preserve DOM order, safest for this site.

## Media Resolution Findings
Episode/player links look like:

https://v.myself-bbs.com/player/play/44169/001

A plain request returned:

    {"message":"Bad Request"}

This worked:

    curl -L -A 'Mozilla/5.0' \
      -e 'https://myself-bbs.com/thread-44169-1-1.html' \
      https://v.myself-bbs.com/player/play/44169/001

Returned player page includes:

    <meta name="generator" content="VPX-Player - v2.0.17">
    <title>P2P HLS Player</title>
    <script src="/static/js/hls.min.js?v=2.0.17"></script>
    <script>
        swarmId = "15180d24dc657e47c5cb302b4c01d96508af0285";
        tid = "44169";
        vid = "001";
        id = "";
    </script>
    <script src="/static/js/vpx-player.min.js?v=2.0.17" type="module"></script>

The public VPX script opens:

    wss://v.myself-bbs.com/ws

And sends:

    {"tid":"44169","vid":"001","id":""}

If response JSON has `status == "ok"`, its `video` field is assigned as the player source.

## Resolver Plan
Create `MediaUrlResolver`.

Resolution order:
1. Fetch episode/player page with:
   - `User-Agent: Mozilla/5.0`
   - `Referer: <series page URL>`
2. Try direct HTML media extraction:
   - `<video src>`
   - `<video><source src>`
   - direct `.mp4`, `.webm`, `.m3u8` links
   - obvious JS variables containing direct media URLs
   - iframe pages only if they expose a normal playable media URL
3. If generic extraction fails and page is VPX:
   - Extract JS values `tid`, `vid`, `id`.
   - Open `wss://v.myself-bbs.com/ws` using OkHttp WebSocket.
   - Send JSON payload.
   - If response status is `ok`, use `video` as media URL.

Native Media3 playback should be feasible if `video` is a standard HLS `.m3u8` URL. Do not implement any DRM/login/payment/captcha bypass.

## Recommended Architecture
Suggested files:

    app/src/main/java/com/example/webplaylist/
      MainActivity.kt
      model/Episode.kt
      parser/EpisodeListParser.kt
      parser/MyselfBbsEpisodeListParser.kt
      resolver/MediaUrlResolver.kt
      resolver/HtmlMediaUrlResolver.kt
      resolver/VpxWebSocketMediaUrlResolver.kt
      data/PlaylistRepository.kt
      data/PlaybackProgressStore.kt
      ui/EpisodeListScreen.kt
      ui/PlayerScreen.kt

MVP can use fewer files, but keep parser and resolver interfaces distinct.

## Current Workspace State
Some initial Gradle scaffold files may already exist:

    settings.gradle.kts
    build.gradle.kts
    app/build.gradle.kts
    app/proguard-rules.pro
    app/src/main/AndroidManifest.xml
    app/src/main/res/values/strings.xml
    app/src/main/res/values/styles.xml

Also likely created empty placeholder launcher PNG files under `app/src/main/res/mipmap-*`; remove/replace them before building.

Important: inspect all files from CLI before continuing. Desktop shell/apply_patch had path issues after WSL switch.

## Suggested Dependencies
Use Kotlin, Compose, Media3, JSoup, OkHttp.

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

OkHttp includes WebSocket support.

## MVP Checklist
1. Verify and clean scaffold. Done in current pass.
   - Removed empty launcher PNGs.
   - Added vector launcher icon.
   - Manifest has `INTERNET`, `LEANBACK_LAUNCHER`, and non-touch feature declarations.

2. Implement parser. Done in current pass.
   - Hardcoded default URL: `https://myself-bbs.com/thread-44169-1-1.html`
   - Parses `ul.main_list > li`.
   - Extracts title and `data-href`.
   - Sorts by `第 NN 話` when all rows have parsed numbers.

3. Implement resolver. Done in current pass.
   - Generic HTML media first.
   - VPX WebSocket second.
   - Returns useful error if unresolved.

4. Implement repository/progress. Done in current pass.
   - Stores episodes in memory.
   - SharedPreferences:
     - last selected series URL
     - last episode index
     - last playback position ms

5. Implement TV UI. Done in current pass.
   - Compose list with clear focus state.
   - Remote-friendly selection.
   - Loading/error states.
   - Resume last watched episode.
   - Direct supported series URL entry from the app.

6. Implement playback. Done in current pass.
   - Media3 ExoPlayer.
   - Resolves media URL lazily.
   - Saves progress on pause/dispose and periodically.
   - On `Player.STATE_ENDED`, increments index and plays next episode.
   - If next episode cannot resolve, shows error.

7. Verify. Blocked in current pass.
   - `./gradlew assembleDebug` could not run because no Gradle wrapper exists.
   - `gradle` is not installed on PATH in this environment.
   - Next step: add a Gradle wrapper or install Gradle, then run debug build.

## README Requirements
README should explain:
- What the app does.
- How to configure/update the hardcoded series URL.
- How the sample page is parsed.
- Specific selectors:
  - `ul.main_list > li`
  - direct title anchor
  - nested `a[data-href]`
- How to build the APK.
- How to install on Google TV via adb.
- How to update selectors if the website changes.
- Limitations:
  - Direct media/HLS extraction only.
  - No DRM/login/payment/captcha bypass.
  - WebView fallback only if native extraction is impossible.

## Desktop Session Notes
The desktop shell became unstable after switching to WSL. Errors included:

    CreateProcess { message: "Rejected(\"Failed to create unified exec process: No such file or directory (os error 2)\")" }

`curl` with approval worked and produced the DOM/resolver findings above. Treat the repo as partially scaffolded and inspect it first from Codex CLI.
