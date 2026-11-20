# Contributing to Auricle for Android

Thanks for working on Auricle for Android. This document outlines the branch model, version synchronization rules, local build workflow, and agent guidance for this repository.

## Using AI Coding Agents

Repository-wide guidance for GitHub Copilot lives in [.github/copilot-instructions.md](.github/copilot-instructions.md), with review ownership in [.github/CODEOWNERS](.github/CODEOWNERS). Changes touching [rust/src/party.rs](rust/src/party.rs) must preserve wire-protocol compatibility (`auricle/party/v1`) with desktop [Auricle](https://github.com/californiaAlmonds/auricle).

## Branch Model

- `main` — stable releases (protected; do not push unreleased breaking work directly without owner review).
- `release/x.y.z` — staging branch for an upcoming release version.
- `feature/*` — working branches cut from the active `release/x.y.z` branch.

```
feature/* ──PR──► release/x.y.z ──PR──► main
```

## Versioning Rules

Keep version fields synchronized across both layers whenever preparing a release branch:

- `versionName` and `versionCode` in [app/build.gradle.kts](app/build.gradle.kts)
- `version` in [rust/Cargo.toml](rust/Cargo.toml)

Feature branches should **not** bump release versions unless explicitly part of release preparation.

## Building Locally

### Prerequisites

- JDK 17+
- Android SDK (`platforms;android-35`, `build-tools;35.0.0`, `ndk;27.2.12479018`)
- Rust `1.91+` with targets `aarch64-linux-android` and `x86_64-linux-android`
- `cargo-ndk`

### Commands

```bash
# 1. Build Rust JNI shared libraries (whenever rust/src/** changes)
cargo ndk -t arm64-v8a -t x86_64 -o app/src/main/jniLibs build --manifest-path rust/Cargo.toml --release

# 2. Run Rust library tests
cargo test --manifest-path rust/Cargo.toml --release --lib

# 3. Compile Kotlin or build debug APK
./gradlew compileDebugKotlin
./gradlew assembleDebug

# 4. Build local debug-signed release split + universal APKs
./gradlew assembleRelease -PsplitApks=true -PallowDebugSignedRelease=true
```

See [README.md](README.md) for production keystore environment variables (`AURICLE_KEYSTORE_PATH`, `AURICLE_KEYSTORE_PASSWORD`, `AURICLE_KEY_ALIAS`, `AURICLE_KEY_PASSWORD`). Never commit `.jks`/`.keystore` files or credentials to the repository.
