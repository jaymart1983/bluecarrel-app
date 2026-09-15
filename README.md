# Bluecarrel

Android companion app for the **Xteink X4 Pro** running the CrossPoint X4 Pro
firmware. It keeps the reader's library, reading positions and firmware in sync
with your own Calibre-Web Automated server, over Bluetooth.

No DRM handling, no Xteink cloud. The app talks to your server and your reader,
nothing else.

## Test builds

- **[Bluecarrel app test release](https://github.com/jaymart1983/bluecarrel-app/releases/tag/v1.0.6-test)** (download the APK on your phone)
- **[Latest firmware release](https://github.com/jaymart1983/bluecarrel-firmware/releases/latest)**
- [All app releases](https://github.com/jaymart1983/bluecarrel-app/releases)

Leave the update page blank in the app's settings to get the latest firmware release. Update page URL, if you set it by hand (tap and hold to copy):

```
https://github.com/jaymart1983/bluecarrel-firmware/releases/latest/download/
```

The test builds use encrypted pairing and signed firmware, so install the firmware first, then the app, then pair from the reader's Settings page.

## What it does

- **Bluetooth link.** Pair once with the code shown on the reader. After that the
  phone is a trusted host and reconnects on its own. The reader shows the phone's
  name.
- **Library.** Browse and search your Calibre-Web Automated catalogue (OPDS).
  Books you mark offline are downloaded to the phone and mirrored to the reader.
  Removing one removes it from both. If the book is open on the reader, the app
  asks first, then closes it on the reader and removes it.
- **Reading now.** The book open on the reader is marked in the app.
- **Positions.** Reading progress is written to the kosync (KOReader sync)
  endpoint of your server while you read, so KOReader on other devices picks up
  where the X4 Pro left off, and the other way round.
- **Updated books.** When Calibre changes a book the reader holds, the new file is
  sent and the reader keeps its place.
- **Background sync.** A Bluetooth scan wakes the app when the reader is nearby,
  and a foreground service keeps the link while connected, so sync works with the
  app closed.
- **Store on the reader.** The reader can browse the catalogue itself; the phone
  answers its requests over Bluetooth.
- **Firmware updates.** The app checks an update page for a newer reader build,
  downloads it, checks its SHA-256 and sends it to the reader. The reader asks
  **Update now / Later / Cancel**; Later installs the next time it sleeps.
  Automatic download can be switched on in Reader settings.

## Requirements

- Android 10 (API 29) or later with Bluetooth LE.
- An Xteink X4 Pro running the CrossPoint X4 Pro firmware
  ([Bluecarrel firmware](https://github.com/jaymart1983/bluecarrel-firmware)).
- A [Calibre-Web Automated](https://github.com/crocodilestick/Calibre-Web-Automated)
  server with OPDS and kosync enabled, reachable from the phone (LAN, VPN or a
  tunnel).

## Setup

1. Install the APK and open the app. Allow Bluetooth and notifications, and allow
   background use when asked (needed for sync with the app closed).
2. **Settings:** enter the server URL (for example `https://books.example.com`)
   and your Calibre-Web account. The OPDS catalogue and kosync both use it.
3. On the reader, tap the Power button to open the Control Centre and tap
   **Settings**. Enter the pairing code it shows into the app.
4. Optional: set an **update page** URL to receive firmware updates (see below).

If the app stops connecting after an update of the app itself, turn the phone's
Bluetooth off and on.

## Hosting firmware updates

The update page is any static web server. Put the firmware image next to a
`firmware.json`:

```json
{
  "version": "20260914.0032",
  "file": "crosspoint-x4pro-20260914.0032.bin",
  "size": 4661136,
  "sha256": "6f2647312e9b52fdfddd029b905472e5936b15f64742ae8a902497d1eeb764b3"
}
```

`version` is the firmware's build stamp (`yyyyMMdd.HHmm`). The app compares it
with the version the reader reports and offers the update when the page is
newer. `size` and `sha256` must describe `file` exactly; the app refuses an image
that does not match.

## Build

Needs JDK 17 and the Android SDK (platform 34, build-tools 34).

```bash
./gradlew assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`. Without a signing key it is
signed with the debug key, which is fine for sideloading. To sign every build
with one key (so new builds install over old ones), copy
`keystore.properties.example` to `keystore.properties` and follow the comment in
it.

When publishing a build, bump both `versionCode` and `versionName` in
`app/build.gradle.kts`; Android only offers an APK as an update when
`versionCode` increases.

## How position sync matches books

kosync keys progress on KOReader's partial-MD5 document hash of the file
contents. The app hashes the exact file it sends to the reader, so a book sent
through the app always matches across devices. A book sideloaded by other means
matches too, as long as it is the byte-identical file.

## License

MIT. See [LICENSE](LICENSE).
