# MojaLoss for Android

A single native Kotlin / Jetpack Compose entertainment app with a Sage, Forest
and Porcelain palette that follows the device's light or dark mode. Stacked
artwork and compact glass surfaces form one Home feed; Search, Explore and My
List open contextual sheets without a bottom navigation bar or rail. The Media3
player adapts to the available window and supported measured folding panes.

## Current milestone

Version 0.1.8 opens all releases directly when the viewer deliberately swipes
right on the last featured title. Forward/left continuation at that same boundary
also opens it. There is no extra card to tap. Short gestures stay on the title,
Back retains selection, and the catalog opens movies and series together with
newest releases first.

Shared finite page fades and press feedback make navigation, cards and supporting
pages consistent. App Reduce motion, disabled system animation or battery saver
turn custom motion off. Player/trailer route ownership transfers instantly;
their existing control animations remain independent of playback.

Home keeps automatic Hindi, English and International movie/series sections.
Title queries explicitly disable Jellyfin's collection grouping, so a server
preference cannot substitute BoxSets for individual Movie/Series cards. Language
source discovery supports explicitly labelled server categories and authorized
library views with bounded requests; unknown-language titles are not guessed
into a language section. Recently uploaded remains a separate, bounded bottom
row sorted by server upload date.

Release discovery requests premiere date descending, then production year
descending and name ascending. Each bounded page falls back to January 1 of a
known release year when the actual date is absent. Separate server sort keys do
not guarantee a global date/year fallback merge across unseen pages; the app
never scans the whole library to manufacture one.

The app retains compact artwork actions, on-demand paging with skeleton cards,
server-confirmed favorites, episode selection/resume, offline downloads and a
Media3 player with gestures and saved Fit/Crop/Stretch modes. Theme follows the
system. Immersive window behavior is implemented; actual system-bar visibility
and physical camera-cutout behavior still need phone acceptance.

Check for updates is available in Settings and Connect. Download and installation
require explicit taps; APK size, digest, package, version and signer must match.
The public source destination is **JuelHossain/Mojaloss**. A public update feed
requires matching APK and update.json release assets; source upload alone does
not publish that feed. See [release setup](docs/GITHUB_RELEASES.md).

Current checks and limits are recorded in
[0.1.8 verification](docs/ux-0.1.8-verification.md). Local fixtures do not prove
that the customer's specific live-server error has been reproduced.

## Build

Install JDK 17 and the Android SDK platform 36/build tools 35.0.0. Set `JAVA_HOME`
and `ANDROID_HOME`, or configure the SDK through Android Studio. The project
includes Gradle 8.13's checksum-pinned wrapper.

```sh
./gradlew :app:assembleDebug
./gradlew :core:model:test :core:ui:testDebugUnitTest \
  :core:data:testDebugUnitTest :core:downloads:testDebugUnitTest \
  :core:player:testDebugUnitTest :app:testDebugUnitTest
./gradlew :app:assembleDebugAndroidTest :core:data:assembleDebugAndroidTest \
  :core:downloads:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
```

`python3 scripts/run-gradle.py <tasks>` is available for environments where Java
needs to receive the standard `HTTP_PROXY` / `HTTPS_PROXY` settings explicitly.
It uses those environment settings, without storing credentials. Source archives
do not include toolchains, caches, local SDK settings or signing keys.

Debug application ID: `com.mojaloss.android.debug`. Release application ID:
`com.mojaloss.android`. APK output: `app/build/outputs/apk/debug/app-debug.apk`.
The delivered APK uses a development signing key; production signing is a later
release step.

The public source omits four optional generated debug-preview PNGs. Its debug
preview uses the existing artwork placeholders when those files are absent; live
media artwork still comes from the verified server. The downloadable test APK
retains the original preview images. No image reconstruction files or encoded
image payloads are published in the source tree.

## Modules and integration

| Module | Responsibility |
| --- | --- |
| `app` | Activity, Navigation 3 routes, ViewModel, screens and adaptive layouts |
| `core:model` | Account-scoped contracts and observed-progress policies |
| `core:ui` | Sage theme, glass components, motion and confirmation feedback |
| `core:data` | Supported authentication, catalog, favorites, progress and private preferences |
| `core:player` | Media3 ownership, tracks, seek observation, bounded recovery and safe transport |
| `core:downloads` | Durable private offline queue/cache, Wi-Fi and storage policy, account boundaries |

Favorites are confirmed by service read-back. A confirmed removal can be undone;
delayed older operations cannot overwrite a newer intent. Account changes cancel
scoped work and clear account metadata/artwork caches. Tokens are encrypted with
Android Keystore and excluded from backup. HTTPS artwork rejects redirects;
authenticated playback resources must remain within the verified session origin.

Playback reports observed advancing positions, including the final observed
position at pause/close. Requested seek targets are never progress writes. Closing
the player is distinct from watched completion; cleanup before any advancement
must preserve existing service resume. Trailer playback does not use the main
progress path. Backgrounding pauses playback and returning never starts it
implicitly. Audio/subtitle preferences are applied when preparing a stream.

## Remaining acceptance work

Real provider behavior, download interruption/offline playback, battery use,
physical haptics, API37 hole-punch and Galaxy Z Fold behavior require customer
phone acceptance. Automatic next-episode playback and casting are not enabled.
The current verification document distinguishes these from local fixture checks.

Source archives retain earlier verification evidence and full Git history.
If a source archive includes `MojaLoss-Android-History.bundle`, restore it with
`git clone -b main MojaLoss-Android-History.bundle MojaLoss-Android`.
