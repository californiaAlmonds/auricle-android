# Auricle for Android

A fast, lightweight native Android music player and **Listen Together (Party)** companion for [Auricle](https://github.com/californiaAlmonds/auricle), built with **Kotlin**, **Jetpack Compose**, **Media3 (ExoPlayer)**, and a **Rust** native engine (`ytmapi-rs`, Innertube extractor, lyrics engine, and **Iroh** QUIC P2P sync).

## Features

- **Cross-Platform Party (Listen Together):** Host a party on Android or Windows Auricle and share a compact `auricle://party/...` invite link. Guests join directly over encrypted **Iroh QUIC P2P** (automatic hole-punching + relay fallback across Wi-Fi and mobile data, zero backend servers or accounts). Everyone in the room shares real-time Play/Pause, Seek, Next/Previous, and Queue controls with RTT-filtered drift correction.
- **Native Frosted-Glass UI & Dynamic Palette:** Dark and Light themes matching Auricle Desktop, 6 accent presets, and real-time album-art vibrant color extraction.
- **Home, Search, Detail & Library:** Live YouTube Music Home shelves, instant search suggestions, filtered search (`Songs`, `Albums`, `Artists`, `Playlists`), full Album/Artist/Playlist views, Liked Songs, Recently Played, and custom local Playlists.
- **Original & Romanized English Lyrics:** Built-in `Original | English` lyrics view with Romanized sing-along pronunciation + English line-by-line meaning for non-Latin tracks (Hindi, Korean, Japanese, etc.).
- **Lock-Screen & Background Playback:** Full Android `MediaSessionService` integration with lock-screen, notification shade, and Bluetooth headset controls routed seamlessly through Party sync.
- **Lightweight & Android 15+ Ready:** ~14.8 MB signed `arm64-v8a` release APK with R8 code/resource shrinking and 16 KB ELF page alignment.

## Architecture

- **`app/` (Kotlin + Jetpack Compose + Media3):** UI screens, dynamic palette theming, `AuriclePlaybackService` (`MediaSessionService` + `ExoPlayer` + `HlsMediaSource`), `PlaybackController` Party sync & drift loop, and `NewPipeExtractor` fallback.
- **`rust/` (`auricle_core` cdylib):** Panic-guarded JNI bridge (`AuricleNative`), `ytmapi-rs` catalog client, `VISIONOS`/`IOS` Innertube stream resolver with 6-hour disk cache, lyrics + Romanization translator, and the canonical cross-platform `party.rs` Iroh P2P engine shared byte-for-byte with Windows Auricle.

## Building from Source

### Prerequisites

- JDK 17+
- Android SDK (`platforms;android-35`, `build-tools;35.0.0`, `ndk;27.2.12479018`)
- Rust 1.91+ with targets `aarch64-linux-android` and `x86_64-linux-android`
- [`cargo-ndk`](https://github.com/bbqsrc/cargo-ndk) (`cargo install cargo-ndk`)

### 1. Build the Rust JNI Libraries

```bash
cd rust
cargo ndk -t arm64-v8a -t x86_64 -o ../app/src/main/jniLibs build --release
```

### 2. Build the Android APK

For a local debug-signed release build:

```bash
./gradlew assembleRelease -PsplitApks=true -PallowDebugSignedRelease=true
```

For a production release signed with your keystore:

```bash
export AURICLE_KEYSTORE_PATH=/path/to/release.jks
export AURICLE_KEYSTORE_PASSWORD=your_store_password
export AURICLE_KEY_ALIAS=auricle
export AURICLE_KEY_PASSWORD=your_key_password
./gradlew assembleRelease -PsplitApks=true
```

Split and universal APKs are emitted under `app/build/outputs/apk/release/`:
- `app-arm64-v8a-release.apk` — Recommended for modern 64-bit Android phones (~15 MB)
- `app-universal-release.apk` — Combined `arm64-v8a` + `x86_64` APK

## License

Licensed under the [GNU General Public License v3.0](LICENSE) (`GPL-3.0-only`).
