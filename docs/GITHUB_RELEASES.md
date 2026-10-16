# GitHub source and app updates

The confirmed public repository is **JuelHossain/Mojaloss**. The customer requires
GitHub operations through MCP. The connected MCP tools can upload source to this
existing repository. They do not currently expose release creation or release-asset
uploads. Source publication and an operational APK update feed are separate results.
Do not report a feed live until the release and exact asset bytes are verified.

## Release identity

| Item | Current build |
| --- | --- |
| Repository | `JuelHossain/Mojaloss` |
| Intended stable tag | `v0.1.8` |
| APK asset | `MojaLoss-Sage-0.1.8-debug.apk` |
| Application ID | `com.mojaloss.android.debug` |
| Version / code | `0.1.8` / `9` |
| Minimum Android API | `26` |
| Certificate SHA-256 | `dd4c36aace6f8c030ea40a5783015040987c94e2353b7ea48ea8522d6152d9d1` |
| Update metadata asset | `update.json` |

Retain the protected development signer. Never commit signing keys, passwords,
server credentials, customer accounts or tokens. A default fresh CI debug key
cannot update the installed customer build. No automatic release-signing workflow
is configured here.

## Prepare exact release assets

Build and finish checks, then freeze the APK. Independently verify its signature
using Android SDK apksigner and extract package/version/minimum API using Android
SDK package inspection. Record exact length and SHA-256. The generator below
hashes bytes but does not extract APK identity or verify signatures itself.

```sh
python3 tools/generate_update_manifest.py generate \
  --apk /absolute/path/MojaLoss-Sage-0.1.8-debug.apk \
  --version-code 9 --version-name 0.1.8 --min-sdk 26 \
  --application-id com.mojaloss.android.debug --installed-version-code 8 \
  --verified-certificate-sha256 dd4c36aace6f8c030ea40a5783015040987c94e2353b7ea48ea8522d6152d9d1 \
  --expected-sha256 VERIFIED_APK_SHA256 \
  --notes-file /absolute/path/release-notes.txt \
  --output /absolute/path/update.json
```

Replace VERIFIED_APK_SHA256 with the independently recorded real digest. The
same arguments plus `validate --manifest /absolute/path/update.json` can check
the prepared manifest; omit `generate`, `--notes-file` and `--output` for that call.
The tool is offline and does not publish, sign or install anything.

## Public feed format

The latest stable GitHub release must contain exactly one matching APK asset and
one `update.json` asset. Metadata fields are `versionCode` (integer), `versionName`
(string), `minSdk` (integer), `applicationId` (string), `assetName` (string), `size`
(integer bytes), `sha256` (lowercase digest), and `notes` (plain text). The entire
metadata file is limited to 64 KiB; the APK to 200 MiB. Do not add a download URL or
certificate field: source location is derived from the verified release and the
trusted signer is pinned in the app.

When an authorized MCP capability supports the missing release operations:

1. Create a draft release/tag for the exact audited source commit; preserve
   existing remote files and branch changes.
2. Attach the frozen signed APK and its matching update.json to the same release.
3. Read back names, byte lengths and available sha256 digests, then publish stable
   (not draft or prerelease), with the intended release marked latest.
4. Read back `https://api.github.com/repos/JuelHossain/Mojaloss/releases/latest`.
   Require exact asset paths under
   `https://github.com/JuelHossain/Mojaloss/releases/download/v0.1.8/`.
   Verify public downloaded bytes against the frozen assets before claiming success.
5. Test from a same-signer previous build: Check for updates, Download, verification,
   explicit Install update, Android confirmation; then confirm installed version
   and retained account/downloads.

A repository-root manifest, source ZIP, Actions artifact or Drive link is not this
feed. Do not bypass an MCP-only instruction with GitHub CLI or browser publishing.
If required tools remain absent, deliver the signed APK and source, and state that
release-asset publishing is blocked.

## App behavior

Checks happen only on request. Download displays actual byte progress, verifies
size/digest and package/version/signer, and opens Android's installer only after
an explicit tap. Android installation confirmation remains required; opening the
installer is not installation success. Interrupted partial downloads restart;
complete verified downloads can recover after process recreation. Media-server
credentials are never sent to GitHub. Failed or unavailable feeds report failure
truthfully. A new APK can be installed manually over the same-signer previous app.
