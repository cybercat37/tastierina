# AGENTS.md

Guide for agents and people working on this repository.

## What this is

Tastierina: a groovebox for learning electronic dance music, one level at a
time, as a native Android app.

- `engine/`: the audio engine, pure Rust with no dependencies. Voices,
  held keyboard notes in nine timbres, metronome, scheduled pads and
  notes, a sequencer of three giri (drums, bass, melody; 1, 2 or 4 bars)
  with giro changes at loop end, a scaletta and live recording, timing judge, SPSC command queue, and
  `repeat` (level 0.5: phrase generation and evaluation), `perform`
  (level 1 "A tempo": a song judged note by note, stars). Renders without allocating,
  locking or blocking.
- `engine-android/`: `libtastierina.so`. Hand-written AAudio binding and the
  JNI functions behind `it.tastierina.app.Engine`. Empty on other platforms.
- `engine-cli/`: renders the sounds to WAV, to listen on a computer.
- `app/`: Kotlin and Jetpack Compose. `Engine.kt` (JNI surface),
  `AppState.kt` (all state, the only caller of `Engine`), `Ui.kt` (levels
  0 and 0.5, shared chrome), `Keyboard.kt` and `Songs.kt` (level 1), `Drums.kt` and
  `Rhythm.kt` (level 2), `Groove.kt` (levels 3 and 4: note rows, recording, the Brano view),
  `Giri.kt` (the stored giri, mirror of the engine's).
- `web/index.html`: the earlier web version, kept as a reference. Every
  level is now native.

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
