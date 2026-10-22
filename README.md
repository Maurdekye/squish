# Squish

Squish is an Android app that shrinks photos and videos so they fit under an app's upload limit.
Right now it works with Discord: as a file picker inside Discord, and as a "Squish for Discord"
entry in the share sheet.

- Only Discord gets shrunk files. Any other app that uses Squish as a picker gets your originals.
- Your originals are never changed. Squish only reads them and writes its copies to its own cache,
  which it clears after two days.
- Files already under the limit are sent as they are.
- Squish never asks for your Discord login or token, and it has no internet permission.

## Install

Download the APK from the [latest release](../../releases/latest) on your phone and open it.
Android will ask you to allow installs from your browser or file manager the first time.

Every release is signed with the same key, so a new version installs over the old one.

## Use it

**From Discord:** tap **+**, then **Files**. In the file screen, open the menu (☰) and choose
**Squish**. Pick your photos or videos. Big ones are shrunk, then attached.

**From your gallery:** share photos or videos and choose **Squish for Discord**. Squish shrinks
what's too big and opens Discord's share screen.

**Your limit:** open the Squish app and pick your plan: Free (20 MB, the default), Nitro Basic
(50 MB), Nitro (500 MB) or a custom number. Squish can't see your plan without your Discord login,
so this is a setting. The app also has a **Try it** button for shrinking files without Discord.

## How it shrinks

- **Videos** are re-encoded on the phone's hardware encoder to H.264 and AAC in an MP4, which every
  Discord client plays. You can switch to H.265 (HEVC) in the app for a sharper picture at the same
  size, but some devices, mostly computers, can't play H.265 inside Discord. The bitrate is worked
  out from the clip's length so the file lands just under the limit. Long clips also get a lower resolution, 60 fps clips drop to 30 fps, and HDR
  clips are converted to normal (SDR) colour. If a video would come out blurry (below 480p),
  Squish warns you first, so you can trim it instead.
- **Photos** are re-saved as JPEG (WebP if they have transparency), lowering quality and then size
  until they fit. Animated GIFs can't be shrunk without losing the animation, so Squish sends them
  as they are and warns you.

Discord's limits were checked on 2026-10-22: Free 20 MB (since 2026-08-13), Nitro Basic 50 MB,
Nitro 500 MB. Squish counts 1 MB as 1,000,000 bytes to stay safely under.

## Build

You need JDK 21 and the Android SDK (`ANDROID_HOME`).

```sh
./gradlew assembleRelease
```

Without a signing key the release APK is unsigned. To sign, point the Gradle property
`squish.signingProperties` at a properties file with `storeFile`, `storePassword`, `keyAlias`
and `keyPassword`, or set `SQUISH_KEYSTORE_FILE`, `SQUISH_KEYSTORE_PASSWORD`, `SQUISH_KEY_ALIAS`
and `SQUISH_KEY_PASSWORD`.

## Releases

GitHub Actions builds an APK for every push and pull request (see the run's artifacts). To publish
a release, set `appVersion` in `app/build.gradle.kts`, add a section for that version to
`CHANGELOG.md` saying what changed (it becomes the release notes), commit, and push a matching tag:

```sh
git tag v0.2.0
git push origin main v0.2.0
```

The release workflow signs the APK with the key in the repository secrets
(`SQUISH_KEYSTORE_BASE64`, `SQUISH_KEYSTORE_PASSWORD`, `SQUISH_KEY_ALIAS`, `SQUISH_KEY_PASSWORD`)
and attaches it, with its SHA-256, to a new GitHub Release.

## Credits

The app icon follows the layout of the "compress" icon from Google's Material Icons (Apache 2.0).
