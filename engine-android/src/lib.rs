//! Tastierina's engine on Android: low-latency output through AAudio, and the
//! JNI functions that `it.tastierina.app.Engine` calls.
//!
//! Only primitives cross JNI, so no JNI helper crate is needed. On other
//! platforms this crate is empty, so `cargo test` works on a computer.

#[cfg(target_os = "android")]
mod aaudio;
#[cfg(target_os = "android")]
mod jni_api;
