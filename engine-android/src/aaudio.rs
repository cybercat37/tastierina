//! A minimal binding to AAudio (Android 8+), enough for one low-latency output
//! stream with a data callback. Hand-written so the build needs no C++ code.

use std::ffi::c_void;
use std::ptr::null_mut;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use engine::timing::Presentation;
use engine::{engine, Control, Renderer};

#[repr(C)]
pub struct AAudioStreamBuilder {
    _private: [u8; 0],
}
#[repr(C)]
pub struct AAudioStream {
    _private: [u8; 0],
}

type DataCallback = unsafe extern "C" fn(*mut AAudioStream, *mut c_void, *mut c_void, i32) -> i32;
type ErrorCallback = unsafe extern "C" fn(*mut AAudioStream, *mut c_void, i32);

const AAUDIO_OK: i32 = 0;
const DIRECTION_OUTPUT: i32 = 0;
const FORMAT_PCM_FLOAT: i32 = 2;
const SHARING_EXCLUSIVE: i32 = 0;
const PERFORMANCE_LOW_LATENCY: i32 = 12;
const CALLBACK_CONTINUE: i32 = 0;
const CLOCK_MONOTONIC: i32 = 1;

#[link(name = "aaudio")]
extern "C" {
    fn AAudio_createStreamBuilder(builder: *mut *mut AAudioStreamBuilder) -> i32;
    fn AAudioStreamBuilder_setDirection(builder: *mut AAudioStreamBuilder, direction: i32);
    fn AAudioStreamBuilder_setSharingMode(builder: *mut AAudioStreamBuilder, mode: i32);
    fn AAudioStreamBuilder_setPerformanceMode(builder: *mut AAudioStreamBuilder, mode: i32);
    fn AAudioStreamBuilder_setFormat(builder: *mut AAudioStreamBuilder, format: i32);
    fn AAudioStreamBuilder_setChannelCount(builder: *mut AAudioStreamBuilder, channels: i32);
    fn AAudioStreamBuilder_setDataCallback(builder: *mut AAudioStreamBuilder, cb: DataCallback, user: *mut c_void);
    fn AAudioStreamBuilder_setErrorCallback(builder: *mut AAudioStreamBuilder, cb: ErrorCallback, user: *mut c_void);
    fn AAudioStreamBuilder_openStream(builder: *mut AAudioStreamBuilder, stream: *mut *mut AAudioStream) -> i32;
    fn AAudioStreamBuilder_delete(builder: *mut AAudioStreamBuilder) -> i32;

    fn AAudioStream_requestStart(stream: *mut AAudioStream) -> i32;
    fn AAudioStream_requestStop(stream: *mut AAudioStream) -> i32;
    fn AAudioStream_close(stream: *mut AAudioStream) -> i32;
    fn AAudioStream_getSampleRate(stream: *mut AAudioStream) -> i32;
    fn AAudioStream_getChannelCount(stream: *mut AAudioStream) -> i32;
    fn AAudioStream_getFramesPerBurst(stream: *mut AAudioStream) -> i32;
    fn AAudioStream_setBufferSizeInFrames(stream: *mut AAudioStream, frames: i32) -> i32;
    fn AAudioStream_getBufferSizeInFrames(stream: *mut AAudioStream) -> i32;
    fn AAudioStream_getSharingMode(stream: *mut AAudioStream) -> i32;
    fn AAudioStream_getTimestamp(stream: *mut AAudioStream, clock: i32, frame: *mut i64, nanos: *mut i64) -> i32;
}

/// What the audio callback owns. Lives in a Box whose pointer AAudio holds.
struct AudioSide {
    renderer: Option<Renderer>,
    channels: usize,
    disconnected: Arc<AtomicBool>,
}

unsafe extern "C" fn on_audio(_s: *mut AAudioStream, user: *mut c_void, data: *mut c_void, frames: i32) -> i32 {
    // SAFETY: `user` is the AudioSide box, alive until the stream is closed;
    // AAudio never runs two callbacks of one stream at once.
    let side = &mut *(user as *mut AudioSide);
    let out = std::slice::from_raw_parts_mut(data as *mut f32, frames.max(0) as usize * side.channels);
    match side.renderer.as_mut() {
        Some(r) => r.render(out, side.channels),
        None => out.fill(0.0),
    }
    CALLBACK_CONTINUE
}

unsafe extern "C" fn on_error(_s: *mut AAudioStream, user: *mut c_void, _error: i32) {
    // Headphones unplugged, device changed...: the stream is dead. Closing it
    // here is not allowed, so flag it and let the UI thread restart.
    let side = &*(user as *const AudioSide);
    side.disconnected.store(true, Ordering::Release);
}

/// A running output stream and the control side of its engine.
pub struct Output {
    stream: *mut AAudioStream,
    side: *mut AudioSide,
    pub control: Control,
    pub sample_rate: f64,
    pub buffer_frames: i32,
    pub exclusive: bool,
    pub disconnected: Arc<AtomicBool>,
}

// SAFETY: the stream handle and side pointer are only touched from the thread
// holding the global lock in jni_api; the callback thread uses `side` only.
unsafe impl Send for Output {}

impl Output {
    pub fn open() -> Result<Output, i32> {
        unsafe {
            let mut builder = null_mut();
            let r = AAudio_createStreamBuilder(&mut builder);
            if r != AAUDIO_OK {
                return Err(r);
            }
            let disconnected = Arc::new(AtomicBool::new(false));
            let side = Box::into_raw(Box::new(AudioSide {
                renderer: None,
                channels: 2,
                disconnected: Arc::clone(&disconnected),
            }));
            AAudioStreamBuilder_setDirection(builder, DIRECTION_OUTPUT);
            AAudioStreamBuilder_setSharingMode(builder, SHARING_EXCLUSIVE); // falls back to shared
            AAudioStreamBuilder_setPerformanceMode(builder, PERFORMANCE_LOW_LATENCY);
            AAudioStreamBuilder_setFormat(builder, FORMAT_PCM_FLOAT);
            AAudioStreamBuilder_setChannelCount(builder, 2);
            AAudioStreamBuilder_setDataCallback(builder, on_audio, side as *mut c_void);
            AAudioStreamBuilder_setErrorCallback(builder, on_error, side as *mut c_void);
            let mut stream = null_mut();
            let r = AAudioStreamBuilder_openStream(builder, &mut stream);
            AAudioStreamBuilder_delete(builder);
            if r != AAUDIO_OK {
                drop(Box::from_raw(side));
                return Err(r);
            }

            // Only now do we know the device's own rate; build the engine for it.
            let sample_rate = AAudioStream_getSampleRate(stream).max(8000) as f64;
            let channels = AAudioStream_getChannelCount(stream).max(1) as usize;
            let (control, renderer) = engine(sample_rate);
            (*side).renderer = Some(renderer);
            (*side).channels = channels;

            // Two bursts of buffer: the usual low-latency setting.
            let burst = AAudioStream_getFramesPerBurst(stream);
            if burst > 0 {
                AAudioStream_setBufferSizeInFrames(stream, burst * 2);
            }
            let buffer_frames = AAudioStream_getBufferSizeInFrames(stream);
            let exclusive = AAudioStream_getSharingMode(stream) == SHARING_EXCLUSIVE;

            let r = AAudioStream_requestStart(stream);
            if r != AAUDIO_OK {
                AAudioStream_close(stream);
                drop(Box::from_raw(side));
                return Err(r);
            }
            Ok(Output { stream, side, control, sample_rate, buffer_frames, exclusive, disconnected })
        }
    }

    /// The frame leaving the speaker at a known monotonic time.
    ///
    /// Before the device reports one (the first few milliseconds), it is
    /// estimated from what was rendered and the buffer size.
    pub fn presentation(&self, now_nanos: i64) -> Presentation {
        let mut frame = 0i64;
        let mut nanos = 0i64;
        let r = unsafe { AAudioStream_getTimestamp(self.stream, CLOCK_MONOTONIC, &mut frame, &mut nanos) };
        if r == AAUDIO_OK && nanos > 0 {
            Presentation { frame, nanos }
        } else {
            let rendered = self.control.shared.frames_rendered() as i64;
            Presentation { frame: rendered - self.buffer_frames as i64, nanos: now_nanos }
        }
    }
}

impl Drop for Output {
    fn drop(&mut self) {
        unsafe {
            AAudioStream_requestStop(self.stream);
            // close() waits for a running callback to finish, so the box is free after it.
            AAudioStream_close(self.stream);
            drop(Box::from_raw(self.side));
        }
    }
}
