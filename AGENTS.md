# AGENTS.md

Guide for agents and people working on this repository.

## What this is

Tastierina: a groovebox for learning electronic dance music, one level at a
time, as a native Android app.

- `engine/`: the audio engine, pure Rust with no dependencies. Voices,
  held keyboard notes in four timbres, metronome, scheduled pads and
  notes, timing judge, SPSC command queue, and
  `repeat` (level 0.5: phrase generation and evaluation). Renders without allocating,
  locking or blocking.
- `engine-android/`: `libtastierina.so`. Hand-written AAudio binding and the
  JNI functions behind `it.tastierina.app.Engine`. Empty on other platforms.
- `engine-cli/`: renders the sounds to WAV, to listen on a computer.
- `app/`: Kotlin and Jetpack Compose. `Engine.kt` (JNI surface),
  `AppState.kt` (all state, the only caller of `Engine`), `Ui.kt` (levels
  0 and 0.5, shared chrome), `Keyboard.kt` and `Songs.kt` (level 1).
- `web/index.html`: the earlier web version with all five levels. It is the
  behavioural spec for the levels still to port.

## Build and test

- `cargo test -p engine`: unit tests and the sound golden test.
- `UPDATE_GOLDEN=1 cargo test -p engine --test golden`: accept a deliberate
  change to how a pad sounds, then commit `engine/tests/golden.txt`.
- `cargo run -p engine-cli -- out/`: WAV files of every pad and the metronome.
- The APK is built by `.github/workflows/apk.yml`: it cross-compiles the
  engine with the NDK into `app/src/main/jniLibs/<abi>/`, then runs
  `./gradlew assembleRelease`. Locally you need the Android SDK and NDK, JDK
  17 and the Rust Android targets.

## Rules

- Nothing on the audio thread may allocate, lock, block, log or panic. New
  communication goes through `Command` and the SPSC queue, or an atomic in
  `Shared`.
- Only primitives cross JNI. Pack richer results into an `i64`, as
  `Judgment::pack` does, and mirror the unpacking in Kotlin.
- Times crossing JNI are monotonic nanoseconds (`System.nanoTime`, touch
  `uptimeMillis × 1e6`): the clock AAudio timestamps use.
- Judge timing on the audio clock (`timing::heard_frame`), never on UI time.
- Every engine change comes with a test; a sound change updates the golden.
- UI copy is Italian.
