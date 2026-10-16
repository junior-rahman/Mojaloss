# MojaLoss 0.1.8 verification

Version 0.1.8 combines bounded Home data fixes, direct release discovery from the
last real featured title, shared motion, and a usable full-height filter panel.
Final candidate 003 passed all 144 JVM checks across 29 suites. Its focused native
checks were blocked by the emulator environment and are not counted as passes. The tested production checkpoint is `2371c4877b0f2d99b59f35e558e13a6eb7de6411`.

## Selected behavior

- A deliberate right swipe from the last real hero title opens Explore directly.
  Forward/left continuation at that boundary also opens it. There is no extra
  See all card. Short/cancelled gestures stay put, navigation dispatches once,
  Back preserves the selected title, and accessibility Previous remains usable.
- The incoming hero card rises from underneath; the outgoing card returns
  centered behind it instead of entering from a horizontal screen edge.
- Shared finite page/state fades and press feedback apply across navigation,
  cards, details, downloads, connection and update states. App Reduce motion,
  disabled system animation and battery saver disable new custom motion.
  Player/trailer routes transfer ownership instantly, preserving their existing
  controls and playback timing.
- Browse filters open fully expanded. The options remain scrollable with the
  keyboard visible; Sort and Apply must actually be displayed before testing
  their actions. The prior partial sheet could conceal the lower form while its
  list considered those controls visible.
- Individual-title requests explicitly disable Jellyfin collection collapse.
  Recently uploaded remains a bounded, independent bottom row; it cannot replace
  the release-first hero or trigger a whole-library scan.
- Hindi, English and International movie/series rows use explicit authorized
  server category/library sources. International movies and International series
  remain separate automatic rows. Unknown language is never guessed.
- Source discovery uses small, cached metadata queries and a bounded authorized
  UserViews fallback. DNS/auth/rate-limit failures do not trigger broader scans.
  Schema failures retain accepted cards and offer sanitized diagnostic reports.
- The updater targets the confirmed repository `JuelHossain/Mojaloss`.

## Contract research

Jellyfin v10.11.0 Folder.cs defaults unspecified CollapseBoxSetItems according to
server grouping settings; this can replace individual titles with BoxSets.
ItemsController exposes an explicit override. Language sources must use actual
authorized server IDs rather than inferred platform or language membership.

- https://github.com/jellyfin/jellyfin/blob/v10.11.0/MediaBrowser.Controller/Entities/Folder.cs
- https://github.com/jellyfin/jellyfin/blob/v10.11.0/Jellyfin.Api/Controllers/ItemsController.cs
- https://github.com/jellyfin/jellyfin/blob/v10.11.0/Jellyfin.Api/Controllers/UserViewsController.cs

This explains a credible shared failure path, not a reproduction of the customer's
exact server error. Development tools never contact the actual MojaLoss service;
all network checks here use local fixture servers.

## Verification ledger

| Scope | Candidate | Result |
| --- | --- | --- |
| All JVM unit suites | 003 | 144 passed across 29 suites; no failures/errors/skips |
| Direct hero swipe and underneath motion | 002 | 11 native methods passed |
| Shared motion, reduced motion and mid-transition touch | 002 | 3 native methods passed |
| Automatic Home sections | 002 | 5 native methods passed |
| Existing touched Home regressions | 002 | 9 native methods passed |
| Corrected cache-restore/library fallback fixture | 002 | 1 native method passed |
| MainActivity preview journeys | 002 | Existing journey passed; new journey exposed the filter viewport defect |
| MainActivity filter/swipe journeys and 320dp-height filter visibility | 003 | Blocked by test infrastructure; no accepted execution |
| Exact APK install over 0.1.7 | 002 | Passed: UID and local private fixture marker retained |
| Final exact APK install over 0.1.7 | 003 | Blocked before installation; not verified |
| Public code-only debug asset merging | 003 | Passed without original PNGs, parts or reconstruction task |

The corrected restore fixture checks that only `/jelly/Users/Me` verifies the
restored account; retained content must not be refetched. Its initial expectation
of two requests was wrong and was corrected without changing production logic.
Candidate 001's three old terminal-CTA methods are retired after the latest
interaction change. Historical failures and retired fixtures remain identifiable
in the separate verification artifact; none are relabelled as passing results.
Core data checks unchanged after candidate 002 are retained with their source
provenance rather than repeatedly executed.

The final MainActivity/filter fixture includes stricter displayed-control checks;
its test-only checkpoint is `e936339b02b65c372a82734dd812ecd3d2b6c8ba`.
The first candidate 003 attempt had an invalid old-APK fixture path and was rejected
before any accepted install/test. The corrected preflight verified exact main,
prior-version and test APK hashes/signers. A leftover emulator PID sentinel then
blocked startup. File-lock probes were free, but an outside-namespace live writer
could not be safely excluded. No lock was removed and no duplicate writer started.
Automatic approval policy rejected outside-sandbox process inspection; it was not
bypassed. The final strict installed-APK gate and three focused tests did not run.
The small-height filter fix is compiled and independently reviewed, with its
actual final-device visibility check still outstanding. This is an infrastructure
limitation, not a passing result or an observed application failure.


## Final APK identity

| Field | Inspected value |
| --- | --- |
| Candidate | 003 |
| Package | `com.mojaloss.android.debug` |
| Version / code | `0.1.8` / `9` |
| Minimum / target API | `26` / `36` |
| Bytes | `38,382,949` |
| APK SHA-256 | `cc820b4957143fc6beb387181e0963d2d16bfd351027c68825b129ffdbee0e24` |
| Signer SHA-256 | `dd4c36aace6f8c030ea40a5783015040987c94e2353b7ea48ea8522d6152d9d1` |
| Local production checkpoint | `2371c4877b0f2d99b59f35e558e13a6eb7de6411` |

Android SDK package and signature tools independently inspected this APK. Its
package and signer match the prior 0.1.7 development installation. The update
manifest is generated from these inspected values and the actual APK size/hash;
command-line generator assertions alone are not package/signature proof.

## Publication

GitHub source is published only through the connected GitHub tools. Automatic
approval review rejected one large binary preview-artwork upload because its
contents/sensitivity could not be sufficiently verified. The safer referenced
source tree omits all four optional PNGs, all reconstruction parts/manifests and
preview provenance metadata. Already accepted image blobs remain unreferenced;
none are included in the published commit. The existing gradient/icon artwork
fallback supports debug previews without these files. The separately delivered
APK retains its local original preview images.

The Gradle image reconstruction task was removed entirely. The code-only checkout
passed actual `:app:mergeDebugAssets` without optional PNGs or reconstruction inputs.
That was an asset-merge check, not a second full code-only APK build.
No signing key, APK, toolchain, account fixture, cache or private recovery archive
belongs in the public source snapshot. A path/blob/mode audit and publication
receipt accompany the separate verification artifact.

Source publication does not create a GitHub APK release or an update feed.
The connected GitHub tools expose source operations, but no release creation or
release-asset upload. `update.json` is prepared for the exact delivered APK;
in-app updates remain unpublished until matching release assets are hosted.

## Acceptance limits

Separate server premiere-date/year sort keys cannot globally coalesce fallback
dates across unseen pages. The app does not fetch the whole catalog to manufacture
that order. Real provider playback/downloads, interrupted transfers, battery use,
physical haptics, API 37 camera cutouts, Fold behavior and actual SystemUI bar
visibility require phone acceptance. Local ATD captures show fixture UI/rendered
frames; they are not physical-system-bar or real-provider evidence.
