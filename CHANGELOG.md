# Changelog

Each release's notes on GitHub come from its section here. Add a section before tagging a release. Keep each bullet on one line, since GitHub shows line breaks in release notes as they are.

## 0.2.0 (2026-10-22)

- New **Video format** setting. H.264 stays the default because every Discord client plays it. H.265 (HEVC) is optional: it keeps more resolution at the same file size, but some devices, mostly computers, can't play it inside Discord. Phones without an H.265 encoder keep using H.264.
- **Try it** now shows each shrunk video's resolution and format, for example "720p H.265".
- The screen stays on while files are being shrunk.

## 0.1.0 (2026-10-22)

First release.

- **Picker:** in Discord, tap **+** → **Files** → ☰ → **Squish**, then pick photos or videos.
- **Share target:** share photos or videos to **Squish for Discord**. Squish shrinks them and opens Discord's share screen.
- Only files over your upload limit are shrunk; everything else is sent as is. Set the limit in the app: Free 20 MB (default), Nitro Basic 50 MB, Nitro 500 MB, or a custom number.
- **Videos** are re-encoded on the phone's hardware encoder to H.264 and AAC in an MP4. The bitrate is worked out from the clip's length so the file lands just under the limit. Long clips get a lower resolution, 60 fps drops to 30 fps, and HDR is converted to normal colour. Clips that were already compressed keep more resolution. Squish warns before a result that will look blurry.
- **Photos** are re-saved as JPEG (WebP when transparent), lowering quality and then size until they fit. Animated GIFs are sent as is, with a warning.
- Only Discord gets shrunk files; other apps get your originals. Your originals are never changed.
