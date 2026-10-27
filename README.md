# Lumenkite

A private photo gallery for Android with free backup to your own Telegram account.

> **Unofficial.** Lumenkite uses the [Telegram API](https://core.telegram.org/api) through
> [TDLib](https://github.com/tdlib/td). It is not made by or affiliated with Telegram.

## Features

- **Gallery** for the photos and videos on your phone, plus albums and a viewer with zoom and video playback.
- **Backup to Telegram.** Original files are uploaded as documents to a private channel
  ("Lumenkite Backup") in your own account. Only you can see it. Lumenkite has no server.
- **Free up space.** Deletes phone copies that are already backed up, after checking each file still exists on Telegram.
- **Vault.** Hidden photos kept in the app's private storage.
- **Explore.** On-device search by objects, text in photos, people (face grouping) and places.

## Privacy

- Your Telegram login and backups go straight from your phone to Telegram. The developer never receives them.
- Face grouping, object labels and text recognition run on the phone. Face data is stored only on the phone and is never uploaded.
- Place names come from Android's `Geocoder`, which sends photo GPS coordinates to Google to look up the city.
- Google ML Kit, used for Explore, may send usage statistics to Google.

## Building

Requirements: Android Studio (or the Android SDK) and JDK 17+.

1. Create your own Telegram API credentials at [my.telegram.org](https://my.telegram.org) → *API development tools*.
2. Copy `local.properties.example` to `local.properties` and fill in `sdk.dir`, `telegram.apiId` and `telegram.apiHash`.
   `local.properties` is git-ignored. Never commit it.
3. Build: `./gradlew assembleDebug` (only `arm64-v8a` builds are produced).

Without API credentials the app still runs. Backup then shows a "Telegram keys missing" screen.

> If you publish a fork, use your **own** `api_id`/`api_hash` (required by the
> [Telegram API Terms](https://core.telegram.org/api/terms)), don't put "Telegram" in the app name,
> and don't use the Telegram logo.

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
