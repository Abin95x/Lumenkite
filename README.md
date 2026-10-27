<div align="center">

# Lumenkite

**A private photo gallery for Android with free, unlimited backup to your own Telegram account.**

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
![Platform: Android 10+](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)
[![Buy Me a Coffee](https://img.shields.io/badge/Buy%20me%20a%20coffee-FFDD00?logo=buymeacoffee&logoColor=black)](https://buymeacoffee.com/abin95x)

</div>

> **Unofficial app.** Lumenkite uses the [Telegram API](https://core.telegram.org/api) through
> [TDLib](https://github.com/tdlib/td), Telegram's official client library. It is not made by or
> affiliated with Telegram.

## Contents

- [Why Lumenkite](#why-lumenkite)
- [Features](#features)
- [Install and use](#install-and-use)
- [How backup works](#how-backup-works)
- [Privacy](#privacy)
- [FAQ](#faq)
- [Building from source](#building-from-source)
- [Releasing](#releasing)
- [Contributing](#contributing)
- [Support the project](#support-the-project)
- [License](#license)

## Why Lumenkite

Cloud photo storage usually means a monthly bill or compressed photos. Telegram gives every account
unlimited cloud storage for files. Lumenkite turns that into a photo backup: your originals go to a
private channel in **your own** Telegram account, in full quality, at no cost.
There's no Lumenkite server and no Lumenkite account.

## Features

**Gallery**
- Fast grid of every photo and video on your phone, grouped by date
- Albums, plus a full-screen viewer with pinch-to-zoom and video playback
- Share, delete and move to the vault from the viewer or with multi-select
- Light and dark themes

**Backup**
- Original-quality upload to a private Telegram channel ("Lumenkite Backup")
- Automatic backup when new photos appear, plus a check every 6 hours
- Options for Wi-Fi only, charging only, and excluding folders
- Live progress, with a notification you can stop from
- Backed-up photos stay in your gallery, marked with a cloud. Opening one downloads it.
- Restore on a new phone by logging into the same Telegram account. The index is rebuilt from the channel.

**Free up space**
- Delete phone copies of photos that are already backed up
- Before deleting, the app checks that each file still exists on Telegram. Anything missing stays on the phone.

**Vault**
- Hide photos in the app's private storage, where other apps and the system gallery can't see them
- Locked with your fingerprint, face or screen lock

**Explore** (on-device)
- **People:** groups photos by face
- **Things:** finds photos by what's in them (beach, dog, food…)
- **Text:** search for words that appear in your photos
- **Places:** browse by city, using the photo's location

## Install and use

You **don't** need a Telegram API key. Released APKs already include one.

1. Download the latest APK from [Releases](../../releases) and install it.
   Requires Android 10 or newer on a 64-bit ARM phone (almost all phones from the last few years).
2. Allow access to photos and videos.
3. Open the **Backup** tab and enter the phone number of your Telegram account.
4. Enter the login code Telegram sends you, and your 2-step verification password if you have one.
5. Done. Lumenkite creates the private "Lumenkite Backup" channel in your account and starts uploading.

## How backup works

- Each photo or video is sent as a **document** (not a compressed photo) to a private channel that only
  your account can see. Notifications for these messages are turned off.
- Each message's caption holds the file's name, date, size and folder, so a new phone can rebuild
  your library from the channel alone.
- Files up to **2 GB** are supported (**4 GB** with Telegram Premium).
- If Telegram asks the app to slow down, the backup pauses and continues on the next run.

> **Important:** if you delete the "Lumenkite Backup" channel or lose your Telegram account, the backups
> are gone. If you also used *Free up space*, those were your only copies.

## Privacy

- Your Telegram login and backups go straight from your phone to Telegram. **The developer never receives
  any of your data.**
- Face grouping, object labels and text recognition run on your phone. Face data is stored only on the
  phone and is never uploaded, not even to Telegram.
- **Place names** come from Android's `Geocoder`, which sends photo GPS coordinates to Google to look up
  the city.
- Google ML Kit, used for Explore, may send usage statistics to Google.
- Android's automatic app backup is turned off, so the app's data (including your Telegram session)
  stays on the phone.

## FAQ

**Is it really free and unlimited?**
Telegram doesn't charge for cloud storage and doesn't advertise a total storage limit. Lumenkite is free.

**Can I see my backups in the normal Telegram app?**
Yes. Open the "Lumenkite Backup" channel. Each file is a message you can download.

**Can I use it on two phones?**
Yes. Log into the same Telegram account. Both phones read and add to the same channel.

**Can Telegram see my photos?**
Files in channels are stored on Telegram's servers like any other cloud chat. They are not end-to-end
encrypted. Only your account can access the channel.

**Can my account get banned for this?**
Lumenkite uses Telegram's official library and respects its rate limits. Telegram's terms don't forbid
backups, but Telegram makes its own decisions about accounts. Keep another copy of anything irreplaceable.

## Building from source

Requirements: Android Studio (or the Android SDK) and JDK 17 or newer.

1. Get your own Telegram API credentials at [my.telegram.org](https://my.telegram.org) → *API development tools*.
2. Copy `local.properties.example` to `local.properties` and fill in `sdk.dir`, `telegram.apiId` and
   `telegram.apiHash`. `local.properties` is git-ignored. **Never commit it.**
3. Build:
   ```sh
   ./gradlew assembleDebug
   ```
   The APK is written to `app/build/outputs/apk/debug/`. Only `arm64-v8a` builds are produced.

Without API credentials the app still runs, and the Backup tab shows a "Telegram keys missing" screen.

**Project layout** (`app/src/main/java/com/skyroll/app/`)

| Folder | What's in it |
| --- | --- |
| `telegram/` | TDLib wrapper: login, the backup channel, upload, download |
| `backup/` | Background backup worker and scheduling (WorkManager) |
| `data/` | Photos on the phone, the backup index (Room), vault, settings |
| `explore/` | On-device labels, text recognition, face grouping, places |
| `ui/` | Jetpack Compose screens, components and theme |

> **Publishing a fork?** Use your **own** `api_id`/`api_hash`, don't put "Telegram" in the app name, and
> don't use the Telegram logo. These are requirements of the
> [Telegram API Terms](https://core.telegram.org/api/terms).

## Releasing

Only the maintainer needs these steps.

1. Make sure `local.properties` has the project's Telegram API credentials. The build puts them into the
   APK, so users don't need their own.
2. Sign the release with a real release keystore, not the debug key. **Back the keystore up:** if it's
   lost, users can't install updates over the old version. The Play Store doesn't accept debug-signed apps.
3. Build with `./gradlew assembleRelease` and attach `app/build/outputs/apk/release/app-release.apk` to a
   GitHub Release (or upload it to the Play Store).

Things to know:

- Anyone with the APK can extract the API credentials from it. This is true for every Telegram app.
  The credentials can't be used to log into anyone's account.
- Every user shares the project's API credentials. If Telegram ever blocks them, backup stops for all
  users until a new version ships with new credentials. Files already on Telegram stay safe.
- The app can't go on F-Droid as is, because Google ML Kit is closed source.

## Contributing

Bug reports and pull requests are welcome.

- **Bugs:** open an issue with your phone model, Android version and steps to reproduce.
- **Pull requests:** keep each one focused on a single change and describe what you tested.
- **Never** include your `local.properties`, API credentials, keystores or Telegram session files.

By contributing, you agree that your contributions are licensed under the GPL-3.0, including the
additional permission described below.

## Support the project

Lumenkite is free, has no ads and doesn't track you. If it saves you a storage bill, you can support it:

<a href="https://buymeacoffee.com/abin95x"><img src="https://img.shields.io/badge/Buy%20me%20a%20coffee-FFDD00?style=for-the-badge&logo=buymeacoffee&logoColor=black" alt="Buy Me a Coffee"></a>

Starring the repo and reporting bugs help too.

## License

Copyright (C) 2026 Abin

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version. See [LICENSE](LICENSE).

**Additional permission under GNU GPL version 3 section 7:** if you modify this program, or any
covered work, by linking or combining it with Google ML Kit or Google Play services libraries (or a
modified version of those libraries), containing parts covered by the terms of their respective
licenses, the licensors of this program grant you additional permission to convey the resulting work.

### Third-party components

| Component | License |
| --- | --- |
| [TDLib](https://github.com/tdlib/td) / [tdlib-android](https://github.com/tdlib-android) | Boost Software License 1.0 |
| [SFace](https://github.com/opencv/opencv_zoo/tree/main/models/face_recognition_sface) face model (`sface_int8.onnx`) | Apache 2.0 |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) | MIT |
| [Geist / Geist Mono](https://github.com/vercel/geist-font) fonts | SIL Open Font License 1.1 |
| AndroidX, Jetpack Compose, Media3, Room, WorkManager | Apache 2.0 |
| [Coil](https://github.com/coil-kt/coil), [Telephoto](https://github.com/saket/telephoto) | Apache 2.0 |
| Kotlin, kotlinx.coroutines | Apache 2.0 |
| [Google ML Kit](https://developers.google.com/ml-kit) | Proprietary ([ML Kit Terms](https://developers.google.com/ml-kit/terms)) |
