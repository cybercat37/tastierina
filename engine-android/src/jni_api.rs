//! JNI entry points for `object Engine` in it.tastierina.app.
//!
//! Every function is called from the UI thread. Times are monotonic
//! nanoseconds (System.nanoTime, and MotionEvent times scaled to ns), the same
//! clock AAudio uses for its timestamps.

use std::ffi::c_void;
use std::sync::atomic::Ordering;
use std::sync::Mutex;

use engine::perform::{HitOutcome, Take};
use engine::repeat::{evaluate, hits_in_bar, make_phrase, Phrase};
use engine::timing::{heard_frame, judge};
use engine::Command;

use crate::aaudio::Output;

type JEnv = *mut c_void;
type JObj = *mut c_void;
type JBoolean = u8;

static OUTPUT: Mutex<Option<Output>> = Mutex::new(None);

/// The round of "Ripeti" being played: the phrase, and the player's latest hits
/// (pad, beat), kept in a ring. The player answers bar after bar.
struct Game {
    phrase: Phrase,
    /// No hit before this beat belongs to the round (the listening and count bars).
    answer_start: f64,
    hits: [(u8, f64); HIT_RING],
    next: usize,
    stored: usize,
}

const HIT_RING: usize = 64;

static GAME: Mutex<Option<Game>> = Mutex::new(None);

/// Bars of a round, in beats from its start: listen at 0, count-in, answer.
const COUNT_BAR: f64 = 4.0;
const ANSWER_BAR: f64 = 8.0;

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
    let pad = pad.clamp(0, 255) as u8;
    with_output(NO_JUDGMENT, |o| {
        o.control.send(Command::Hit { pad, velocity });
        match o.control.shared.grid() {
            Some(grid) => {
                let frame = heard_frame(o.presentation(touch_nanos), touch_nanos, o.sample_rate);
                record_attempt_hit(pad, grid.position(frame));
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

fn record_attempt_hit(pad: u8, beat: f64) {
    if let Ok(mut guard) = GAME.lock() {
        if let Some(g) = guard.as_mut() {
            if beat >= g.answer_start - 1.0 {
                g.hits[g.next] = (pad, beat);
                g.next = (g.next + 1) % HIT_RING;
                g.stored = (g.stored + 1).min(HIT_RING);
            }
        }
    }
}

/// Starts a round of "Ripeti": the phrase plays in the bar starting at
/// `listen_start` (a beat of the running metronome), the next bar counts in
/// 4-3-2-1, and the player answers from the bar after that, bar after bar. With `keep`, the previous phrase is played again. Returns the
/// packed phrase (engine::repeat::Phrase::pack).
#[no_mangle]
pub extern "system" fn Java_it_tastierina_app_Engine_nativeRepeatRound(
    _env: JEnv,
    _this: JObj,
    round: i32,
    listen_start: i64,
    keep: JBoolean,
    seed: i64,
) -> i64 {
    // Locks are taken one at a time, always GAME after OUTPUT is released (nativeHit takes OUTPUT then GAME).
    let previous = GAME.lock().ok().and_then(|g| g.as_ref().map(|g| g.phrase));
    let phrase = match (keep != 0, previous) {
        (true, Some(p)) => p,
        _ => make_phrase(round.max(1) as u32, (seed ^ (seed >> 32)) as u32),
    };
    let start = listen_start as f64;
    with_output((), |o| {
        for n in phrase.notes() {
            o.control.send(Command::Schedule { pad: n.pad, beat: start + n.beat() });
        }
        for k in 0..4 {
            o.control.send(Command::ScheduleCount { beat: start + COUNT_BAR + k as f64, go: k == 3 });
        }
    });
    if let Ok(mut game) = GAME.lock() {
        *game = Some(Game { phrase, answer_start: start + ANSWER_BAR, hits: [(0, 0.0); HIT_RING], next: 0, stored: 0 });
    }
    phrase.pack()
}

/// Judges the player's bar starting at beat `bar_start` (engine::repeat::Evaluation::pack).
#[no_mangle]
pub extern "system" fn Java_it_tastierina_app_Engine_nativeRepeatResult(_env: JEnv, _this: JObj, bar_start: i64) -> i64 {
    let ms_per_beat = with_output(None, |o| {
        o.control.shared.grid().map(|g| g.frames_per_beat / o.sample_rate * 1000.0)
    });
    let Ok(guard) = GAME.lock() else { return 0 };
    let Some(g) = guard.as_ref() else { return 0 };
    let mut bar = [(0u8, 0.0f64); HIT_RING];
    let n = hits_in_bar(&g.hits[..g.stored], bar_start as f64, &mut bar);
    evaluate(&g.phrase, &bar[..n], ms_per_beat.unwrap_or(500.0)).pack()
}

#[no_mangle]
pub extern "system" fn Java_it_tastierina_app_Engine_nativeRepeatStop(_env: JEnv, _this: JObj) {
    if let Ok(mut guard) = GAME.lock() {
        guard.take();
    }
    with_output((), |o| {
        o.control.send(Command::ClearScheduled);
    });
}

// ---- Keyboard (level 1) ----------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_it_tastierina_app_Engine_nativeNoteOn(
    _env: JEnv,
    _this: JObj,
    midi: i32,
    timbre: i32,
    velocity: f32,
) {
    with_output((), |o| {
        o.control.send(Command::NoteOn { midi: midi.clamp(0, 127) as u8, timbre: timbre.clamp(0, 255) as u8, velocity });
    })
}

#[no_mangle]
pub extern "system" fn Java_it_tastierina_app_Engine_nativeNoteOff(_env: JEnv, _this: JObj, midi: i32) {
    with_output((), |o| {
        o.control.send(Command::NoteOff { midi: midi.clamp(0, 127) as u8 });
    })
}

#[no_mangle]
pub extern "system" fn Java_it_tastierina_app_Engine_nativeAllNotesOff(_env: JEnv, _this: JObj) {
    with_output((), |o| {
        o.control.send(Command::AllNotesOff);
    })
}

/// A note at `beat` of the running metronome, held `length` beats (for "Ascolta").
#[no_mangle]
pub extern "system" fn Java_it_tastierina_app_Engine_nativeScheduleNote(
    _env: JEnv,
    _this: JObj,
    midi: i32,
    timbre: i32,
    beat: f64,
    length: f64,
) {
    with_output((), |o| {
        o.control.send(Command::ScheduleNote {
            midi: midi.clamp(0, 127) as u8,
            timbre: timbre.clamp(0, 255) as u8,
            beat,
            length,
        });
    })
}

#[no_mangle]
pub extern "system" fn Java_it_tastierina_app_Engine_nativeClearScheduled(_env: JEnv, _this: JObj) {
    with_output((), |o| {
        o.control.send(Command::ClearScheduled);
        o.control.send(Command::AllNotesOff);
    })
}

/// Whether the metronome ticks are heard; its beat grid runs either way.
#[no_mangle]
pub extern "system" fn Java_it_tastierina_app_Engine_nativeSetClick(_env: JEnv, _this: JObj, on: JBoolean) {
    with_output((), |o| {
        o.control.send(Command::Click(on != 0));
    })
}

// ---- "A tempo" (level 1): a song judged note by note ---------------------

static TAKE: Mutex<Option<Take>> = Mutex::new(None);

/// The beat heard at `nanos` and the beat length in ms, if the metronome runs.
fn heard_beat(nanos: i64) -> Option<(f64, f64)> {
    with_output(None, |o| {
        o.control.shared.grid().map(|g| {
            let frame = heard_frame(o.presentation(nanos), nanos, o.sample_rate);
            (g.position(frame), g.frames_per_beat / o.sample_rate * 1000.0)
        })
    })
}

fn with_take<R>(default: R, f: impl FnOnce(&mut Take) -> R) -> R {
    match TAKE.lock() {
        Ok(mut guard) => f(guard.get_or_insert_with(Take::default)),
        Err(_) => default,
    }
}

#[no_mangle]
pub extern "system" fn Java_it_tastierina_app_Engine_nativeTakeReset(_env: JEnv, _this: JObj) {
    with_take((), |t| t.reset())
}

/// Adds an expected note: `key` of the 18, at `beat` of the metronome grid.
#[no_mangle]
pub extern "system" fn Java_it_tastierina_app_Engine_nativeTakeAddNote(_env: JEnv, _this: JObj, key: i32, beat: f64) -> JBoolean {
    with_take(0, |t| t.add_note(key.clamp(0, 255) as u8, beat) as JBoolean)
}

/// A key played at `touch_nanos`. Returns note index << 8 | mark, -1 for a
/// wrong hit, -2 when the metronome is not running.
#[no_mangle]
pub extern "system" fn Java_it_tastierina_app_Engine_nativeTakeHit(_env: JEnv, _this: JObj, key: i32, touch_nanos: i64) -> i64 {
    // OUTPUT is released before TAKE is taken.
    let Some((beat, ms_per_beat)) = heard_beat(touch_nanos) else { return -2 };
    with_take(-2, |t| match t.hit(key.clamp(0, 255) as u8, beat, ms_per_beat) {
        HitOutcome::Note { index, mark } => ((index as i64) << 8) | mark as i64,
        HitOutcome::Wrong => -1,
    })
}

/// Marks the notes whose window closed by `now_nanos` as missed; returns how many.
#[no_mangle]
pub extern "system" fn Java_it_tastierina_app_Engine_nativeTakeExpire(_env: JEnv, _this: JObj, now_nanos: i64) -> i32 {
    let Some((beat, ms_per_beat)) = heard_beat(now_nanos) else { return 0 };
    with_take(0, |t| t.expire(beat, ms_per_beat) as i32)
}

/// Mark of note `i`: 0 pending, 1 on time, 2 early, 3 late, 4 missed.
#[no_mangle]
pub extern "system" fn Java_it_tastierina_app_Engine_nativeTakeMark(_env: JEnv, _this: JObj, i: i32) -> i32 {
    with_take(0, |t| t.mark(i.max(0) as usize) as i32)
}

/// First note still to play, or -1 when the take is over.
#[no_mangle]
pub extern "system" fn Java_it_tastierina_app_Engine_nativeTakeNextPending(_env: JEnv, _this: JObj) -> i32 {
    with_take(-1, |t| t.next_pending().map_or(-1, |i| i as i32))
}

/// engine::perform::Summary::pack.
#[no_mangle]
pub extern "system" fn Java_it_tastierina_app_Engine_nativeTakeSummary(_env: JEnv, _this: JObj) -> i64 {
    with_take(0, |t| t.summary().pack())
}

/// A count-in beep at `beat` of the running metronome (`go` for the last count).
#[no_mangle]
pub extern "system" fn Java_it_tastierina_app_Engine_nativeScheduleCount(_env: JEnv, _this: JObj, beat: f64, go: JBoolean) {
    with_output((), |o| {
        o.control.send(Command::ScheduleCount { beat, go: go != 0 });
    })
}

// ---- Drum machine (level 2) -------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_it_tastierina_app_Engine_nativeSetStep(_env: JEnv, _this: JObj, track: i32, step: i32, on: JBoolean) {
    with_output((), |o| {
        o.control.send(Command::SetStep { track: track.clamp(0, 255) as u8, step: step.clamp(0, 255) as u8, on: on != 0 });
    })
}

/// A whole row: bit s of `steps` is step s.
#[no_mangle]
pub extern "system" fn Java_it_tastierina_app_Engine_nativeSetPattern(_env: JEnv, _this: JObj, track: i32, steps: i32) {
    with_output((), |o| {
        o.control.send(Command::SetPattern { track: track.clamp(0, 255) as u8, steps: (steps & 0xFFFF) as u16 });
    })
}

/// Starts or stops the drum machine on the running metronome.
#[no_mangle]
pub extern "system" fn Java_it_tastierina_app_Engine_nativeSequencer(_env: JEnv, _this: JObj, on: JBoolean) {
    with_output((), |o| {
        o.control.send(Command::Sequencer(on != 0));
    })
}

#[no_mangle]
pub extern "system" fn Java_it_tastierina_app_Engine_nativeDrum(_env: JEnv, _this: JObj, track: i32) {
    with_output((), |o| {
        o.control.send(Command::Drum { track: track.clamp(0, 255) as u8 });
    })
}

// ---- Note rows (level 3): 0 bass, 1 melody ------------------------------------

/// Puts `key` (0..18, -1 for silence) on a step of a note row.
#[no_mangle]
pub extern "system" fn Java_it_tastierina_app_Engine_nativeSetNote(_env: JEnv, _this: JObj, row: i32, step: i32, key: i32) {
    with_output((), |o| {
        o.control.send(Command::SetNote { row: row.clamp(0, 255) as u8, step: step.clamp(0, 255) as u8, key: key.clamp(-1, 17) as i8 });
    })
}

/// Records a key played at `touch_nanos` on the step nearest to what the player
/// was hearing. Returns that step (0..16), or -1 when the metronome is off.
#[no_mangle]
pub extern "system" fn Java_it_tastierina_app_Engine_nativeRecord(_env: JEnv, _this: JObj, row: i32, key: i32, touch_nanos: i64) -> i32 {
    with_output(-1, |o| match o.control.shared.grid() {
        Some(grid) => {
            let frame = heard_frame(o.presentation(touch_nanos), touch_nanos, o.sample_rate);
            let abs_step = (grid.position(frame) * 4.0).round() as i64;
            o.control.send(Command::RecordNote { row: row.clamp(0, 255) as u8, abs_step, key: key.clamp(-1, 17) as i8 });
            abs_step.rem_euclid(engine::DRUM_STEPS) as i32
        }
        None => -1,
    })
}

/// Bass filter, 0 dark to 1 bright.
#[no_mangle]
pub extern "system" fn Java_it_tastierina_app_Engine_nativeBassFilter(_env: JEnv, _this: JObj, setting: f32) {
    with_output((), |o| {
        o.control.send(Command::BassFilter(setting));
    })
}
