//! JNI entry points for `object Engine` in it.tastierina.app.
//!
//! Every function is called from the UI thread. Times are monotonic
//! nanoseconds (System.nanoTime, and MotionEvent times scaled to ns), the same
//! clock AAudio uses for its timestamps.

use std::ffi::c_void;
use std::sync::atomic::Ordering;
use std::sync::Mutex;

use engine::timing::{heard_frame, judge};
use engine::Command;

use crate::aaudio::Output;

type JEnv = *mut c_void;
type JObj = *mut c_void;
type JBoolean = u8;

static OUTPUT: Mutex<Option<Output>> = Mutex::new(None);

/// No judgment: the metronome is off.
const NO_JUDGMENT: i64 = i64::MIN;

fn with_output<R>(default: R, f: impl FnOnce(&mut Output) -> R) -> R {
    match OUTPUT.lock() {
        Ok(mut guard) => match guard.as_mut() {
            Some(out) => f(out),
            None => default,
        },
        Err(_) => default,
    }
}

/// Opens the audio output. Returns true when it is playing.
#[no_mangle]
pub extern "system" fn Java_it_tastierina_app_Engine_nativeStart(_env: JEnv, _this: JObj) -> JBoolean {
    let Ok(mut guard) = OUTPUT.lock() else { return 0 };
    if guard.is_some() {
        return 1;
    }
    match Output::open() {
        Ok(out) => {
            *guard = Some(out);
            1
        }
        Err(_) => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_it_tastierina_app_Engine_nativeStop(_env: JEnv, _this: JObj) {
    if let Ok(mut guard) = OUTPUT.lock() {
        guard.take(); // Drop stops and closes the stream
    }
}

/// True once the device went away (e.g. headphones unplugged): call stop, then start.
#[no_mangle]
pub extern "system" fn Java_it_tastierina_app_Engine_nativeNeedsRestart(_env: JEnv, _this: JObj) -> JBoolean {
    with_output(0, |o| o.disconnected.load(Ordering::Acquire) as JBoolean)
}

/// Plays `pad` now and, if the metronome runs, judges the touch made at
/// `touch_nanos` against the beat the player was hearing at that moment.
#[no_mangle]
pub extern "system" fn Java_it_tastierina_app_Engine_nativeHit(
    _env: JEnv,
    _this: JObj,
    pad: i32,
    velocity: f32,
    touch_nanos: i64,
) -> i64 {
    with_output(NO_JUDGMENT, |o| {
        o.control.send(Command::Hit { pad: pad.clamp(0, 255) as u8, velocity });
        match o.control.shared.grid() {
            Some(grid) => {
                let frame = heard_frame(o.presentation(touch_nanos), touch_nanos, o.sample_rate);
                judge(grid, frame, o.sample_rate).pack()
            }
            None => NO_JUDGMENT,
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_it_tastierina_app_Engine_nativeSetMetronome(
    _env: JEnv,
    _this: JObj,
    on: JBoolean,
    bpm: f32,
) {
    with_output((), |o| {
        o.control.send(Command::Metronome { on: on != 0, bpm });
    })
}

#[no_mangle]
pub extern "system" fn Java_it_tastierina_app_Engine_nativeSetVolume(_env: JEnv, _this: JObj, volume: f32) {
    with_output((), |o| {
        o.control.send(Command::Volume(volume));
    })
}

/// Beats since beat 0 as heard at `now_nanos`, or NaN when the metronome is off.
#[no_mangle]
pub extern "system" fn Java_it_tastierina_app_Engine_nativeBeatPosition(_env: JEnv, _this: JObj, now_nanos: i64) -> f64 {
    with_output(f64::NAN, |o| match o.control.shared.grid() {
        Some(grid) => grid.position(heard_frame(o.presentation(now_nanos), now_nanos, o.sample_rate)),
        None => f64::NAN,
    })
}

/// Time between rendering a sound and hearing it, in milliseconds.
#[no_mangle]
pub extern "system" fn Java_it_tastierina_app_Engine_nativeLatencyMs(_env: JEnv, _this: JObj, now_nanos: i64) -> f32 {
    with_output(-1.0, |o| {
        let heard = heard_frame(o.presentation(now_nanos), now_nanos, o.sample_rate);
        let rendered = o.control.shared.frames_rendered() as f64;
        (((rendered - heard) / o.sample_rate) * 1000.0).max(0.0) as f32
    })
}

/// 1 if the stream got the exclusive (lowest-latency) path, 0 if shared, -1 if closed.
#[no_mangle]
pub extern "system" fn Java_it_tastierina_app_Engine_nativeIsExclusive(_env: JEnv, _this: JObj) -> i32 {
    with_output(-1, |o| o.exclusive as i32)
}

#[no_mangle]
pub extern "system" fn Java_it_tastierina_app_Engine_nativeSampleRate(_env: JEnv, _this: JObj) -> i32 {
    with_output(0, |o| o.sample_rate as i32)
}
