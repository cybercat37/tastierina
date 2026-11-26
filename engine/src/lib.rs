//! Tastierina's audio engine.
//!
//! The UI talks to it only through [`Control`] (commands in, a few atomics
//! out); the audio callback owns the [`Renderer`]. Rendering never allocates,
//! locks or blocks: commands arrive through a fixed SPSC queue and voices come
//! from a fixed pool.

pub mod dsp;
pub mod repeat;
pub mod spsc;
pub mod timing;
pub mod voices;

use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;

use dsp::soft_clip;
use timing::BeatGrid;
use voices::{Pad, Sound, Voice};

pub const MAX_VOICES: usize = 24;
pub const QUEUE_LEN: usize = 256;
/// Pads waiting to be played at a future beat.
pub const MAX_SCHEDULED: usize = 32;
/// Codes in the schedule above the pads: count-in beeps.
const CODE_COUNT: u8 = 254;
const CODE_COUNT_GO: u8 = 255;
pub const BPM_MIN: f32 = 50.0;
pub const BPM_MAX: f32 = 180.0;

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Command {
    /// Play a pad now.
    Hit { pad: u8, velocity: f32 },
    /// Start, retime or stop the metronome.
    Metronome { on: bool, bpm: f32 },
    /// Master volume, 0..1.
    Volume(f32),
    /// Play a pad at a beat position of the metronome grid (ignored when the
    /// metronome is off, or when the beat has already passed).
    Schedule { pad: u8, beat: f64 },
    /// A count-in beep at a beat (`go` for the last count). Same rules as `Schedule`.
    ScheduleCount { beat: f64, go: bool },
    /// Forget every scheduled pad.
    ClearScheduled,
}

/// State the audio thread publishes for the UI thread.
#[derive(Debug)]
pub struct Shared {
    pub sample_rate: f64,
    /// Frames rendered so far (the next frame to render).
    frames_rendered: AtomicU64,
    metronome_on: AtomicBool,
    /// Beat grid as f64 bits, valid while `metronome_on`.
    grid_origin: AtomicU64,
    grid_fpb: AtomicU64,
}

impl Shared {
    pub fn frames_rendered(&self) -> u64 {
        self.frames_rendered.load(Ordering::Acquire)
    }

    /// The beat grid the audio thread is playing, if the metronome is on.
    pub fn grid(&self) -> Option<BeatGrid> {
        if !self.metronome_on.load(Ordering::Acquire) {
            return None;
        }
        Some(BeatGrid {
            origin: f64::from_bits(self.grid_origin.load(Ordering::Acquire)),
            frames_per_beat: f64::from_bits(self.grid_fpb.load(Ordering::Acquire)),
        })
    }
}

/// The UI side: sends commands, reads what the audio thread published.
pub struct Control {
    tx: spsc::Producer<Command, QUEUE_LEN>,
    pub shared: Arc<Shared>,
}

impl Control {
    /// Queues a command; `false` if the audio thread is not keeping up.
    pub fn send(&mut self, cmd: Command) -> bool {
        self.tx.push(cmd)
    }
}

struct Metronome {
    on: bool,
    grid: BeatGrid,
    next_beat: i64,
}

/// The audio side: owned by the callback.
pub struct Renderer {
    sample_rate: f32,
    voices: [Voice; MAX_VOICES],
    rx: spsc::Consumer<Command, QUEUE_LEN>,
    shared: Arc<Shared>,
    frame: u64,
    metronome: Metronome,
    volume: f32,
    seed: u32,
    /// (pad, beat) slots; `None` is free.
    scheduled: [Option<(u8, f64)>; MAX_SCHEDULED],
    scheduled_count: usize,
}

/// Makes a connected pair for a stream running at `sample_rate`.
pub fn engine(sample_rate: f64) -> (Control, Renderer) {
    let (tx, rx) = spsc::channel::<Command, QUEUE_LEN>();
    let shared = Arc::new(Shared {
        sample_rate,
        frames_rendered: AtomicU64::new(0),
        metronome_on: AtomicBool::new(false),
        grid_origin: AtomicU64::new(0f64.to_bits()),
        grid_fpb: AtomicU64::new(1f64.to_bits()),
    });
    let renderer = Renderer {
        sample_rate: sample_rate as f32,
        voices: [Voice::silent(); MAX_VOICES],
        rx,
        shared: Arc::clone(&shared),
        frame: 0,
        metronome: Metronome { on: false, grid: BeatGrid::new(0.0, 90.0, sample_rate), next_beat: 0 },
        volume: 0.8,
        seed: 0x9E37_79B9,
        scheduled: [None; MAX_SCHEDULED],
        scheduled_count: 0,
    };
    (Control { tx, shared }, renderer)
}

impl Renderer {
    /// Fills `out` with interleaved frames of `channels` samples each.
    pub fn render(&mut self, out: &mut [f32], channels: usize) {
        let channels = channels.max(1);
        while let Some(cmd) = self.rx.pop() {
            self.apply(cmd);
        }
        for frame in out.chunks_exact_mut(channels) {
            if self.metronome.on {
                let due = self.metronome.grid.frame_of(self.metronome.next_beat);
                if self.frame as f64 >= due {
                    let accent = self.metronome.next_beat.rem_euclid(4) == 0;
                    self.start(Sound::Tick(accent), 1.0);
                    self.metronome.next_beat += 1;
                }
                if self.scheduled_count > 0 {
                    self.play_due();
                }
            }
            let mut mix = 0.0;
            for v in self.voices.iter_mut() {
                mix += v.next();
            }
            let s = soft_clip(mix * self.volume);
            frame.fill(s);
            self.frame += 1;
        }
        self.shared.frames_rendered.store(self.frame, Ordering::Release);
    }

    fn apply(&mut self, cmd: Command) {
        match cmd {
            Command::Hit { pad, velocity } => {
                if let Some(p) = Pad::from_index(pad) {
                    self.start(Sound::Pad(p), velocity);
                }
            }
            Command::Metronome { on: false, .. } => {
                self.metronome.on = false;
                self.scheduled = [None; MAX_SCHEDULED];
                self.scheduled_count = 0;
                self.shared.metronome_on.store(false, Ordering::Release);
            }
            Command::Metronome { on: true, bpm } => {
                let bpm = bpm.clamp(BPM_MIN, BPM_MAX) as f64;
                let sr = self.sample_rate as f64;
                let now = self.frame as f64;
                let grid = if self.metronome.on {
                    self.metronome.grid.retimed(now, bpm, sr)
                } else {
                    // First beat a little after the button press, so it is never cut.
                    BeatGrid::new(now + sr * 0.15, bpm, sr)
                };
                self.metronome.grid = grid;
                self.metronome.next_beat = grid.position(now).ceil().max(0.0) as i64;
                self.metronome.on = true;
                self.shared.grid_origin.store(grid.origin.to_bits(), Ordering::Release);
                self.shared.grid_fpb.store(grid.frames_per_beat.to_bits(), Ordering::Release);
                self.shared.metronome_on.store(true, Ordering::Release);
            }
            Command::Volume(v) => self.volume = v.clamp(0.0, 1.0),
            Command::Schedule { pad, beat } => {
                if Pad::from_index(pad).is_some() {
                    self.schedule(pad, beat);
                }
            }
            Command::ScheduleCount { beat, go } => self.schedule(if go { CODE_COUNT_GO } else { CODE_COUNT }, beat),
            Command::ClearScheduled => {
                self.scheduled = [None; MAX_SCHEDULED];
                self.scheduled_count = 0;
            }
        }
    }

    fn schedule(&mut self, code: u8, beat: f64) {
        let past = self.metronome.on && self.metronome.grid.position(self.frame as f64) > beat;
        if !self.metronome.on || past {
            return;
        }
        if let Some(slot) = self.scheduled.iter_mut().find(|s| s.is_none()) {
            *slot = Some((code, beat));
            self.scheduled_count += 1;
        }
    }

    fn play_due(&mut self) {
        let pos = self.metronome.grid.position(self.frame as f64);
        for i in 0..MAX_SCHEDULED {
            if let Some((pad, beat)) = self.scheduled[i] {
                if pos >= beat {
                    self.scheduled[i] = None;
                    self.scheduled_count -= 1;
                    match pad {
                        CODE_COUNT => self.start(Sound::Count(false), 1.0),
                        CODE_COUNT_GO => self.start(Sound::Count(true), 1.0),
                        _ => {
                            if let Some(p) = Pad::from_index(pad) {
                                self.start(Sound::Pad(p), 1.0);
                            }
                        }
                    }
                }
            }
        }
    }

    fn start(&mut self, sound: Sound, velocity: f32) {
        // A free voice, else steal the oldest.
        let idx = self
            .voices
            .iter()
            .position(|v| !v.is_active())
            .unwrap_or_else(|| {
                let mut oldest = 0;
                for (i, v) in self.voices.iter().enumerate() {
                    if v.age() > self.voices[oldest].age() {
                        oldest = i;
                    }
                }
                oldest
            });
        self.seed = self.seed.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
        self.voices[idx].trigger(sound, velocity, self.sample_rate, self.seed);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SR: f64 = 48_000.0;

    fn run(r: &mut Renderer, frames: usize) -> Vec<f32> {
        let mut out = vec![0.0; frames * 2];
        // Render in device-sized blocks, like a real callback.
        for block in out.chunks_mut(192 * 2) {
            r.render(block, 2);
        }
        out.chunks_exact(2).map(|f| f[0]).collect()
    }

    #[test]
    fn silence_until_something_happens() {
        let (_c, mut r) = engine(SR);
        assert!(run(&mut r, 4800).iter().all(|&s| s == 0.0));
    }

    #[test]
    fn a_hit_sounds_in_the_next_block() {
        let (mut c, mut r) = engine(SR);
        run(&mut r, 960);
        assert!(c.send(Command::Hit { pad: 0, velocity: 1.0 }));
        let out = run(&mut r, 960);
        assert!(out[..192].iter().any(|s| s.abs() > 0.01));
    }

    #[test]
    fn metronome_ticks_land_on_the_grid() {
        let (mut c, mut r) = engine(SR);
        c.send(Command::Metronome { on: true, bpm: 120.0 });
        let out = run(&mut r, 48_000 * 3);
        let grid = c.shared.grid().expect("metronome running");
        assert!((grid.frames_per_beat - 24_000.0).abs() < 1e-9);
        for beat in 0..4 {
            let f = grid.frame_of(beat).ceil() as usize;
            assert!(out[f.saturating_sub(10)..f].iter().all(|s| s.abs() < 1e-3), "silence before beat {beat}");
            assert!(out[f..f + 200].iter().any(|s| s.abs() > 0.05), "tick at beat {beat}");
        }
        assert_eq!(c.shared.frames_rendered(), 48_000 * 3);
    }

    #[test]
    fn stopping_the_metronome_stops_the_ticks() {
        let (mut c, mut r) = engine(SR);
        c.send(Command::Metronome { on: true, bpm: 120.0 });
        run(&mut r, 48_000);
        c.send(Command::Metronome { on: false, bpm: 120.0 });
        let out = run(&mut r, 48_000);
        assert!(out[4800..].iter().all(|s| s.abs() < 1e-3));
        assert!(c.shared.grid().is_none());
    }

    #[test]
    fn scheduled_pads_play_on_their_beat() {
        let (mut c, mut r) = engine(SR);
        c.send(Command::Metronome { on: true, bpm: 120.0 });
        c.send(Command::Volume(1.0));
        run(&mut r, 480);
        let grid = c.shared.grid().unwrap();
        // A tom at beat 2.5 (between ticks): silence before, sound right after.
        c.send(Command::Schedule { pad: 4, beat: 2.5 });
        c.send(Command::Schedule { pad: 4, beat: -1.0 }); // already past: dropped
        let out = run(&mut r, 48_000 * 2);
        let at = grid.origin + 2.5 * grid.frames_per_beat - 480.0;
        let at = at.ceil() as usize;
        assert!(out[at - 2000..at].iter().all(|s| s.abs() < 1e-3), "nothing before the beat");
        assert!(out[at..at + 400].iter().any(|s| s.abs() > 0.1), "the tom on the beat");
    }

    #[test]
    fn count_beeps_play_on_their_beats() {
        let (mut c, mut r) = engine(SR);
        c.send(Command::Metronome { on: true, bpm: 120.0 });
        run(&mut r, 480);
        let grid = c.shared.grid().unwrap();
        c.send(Command::ScheduleCount { beat: 1.5, go: true });
        let out = run(&mut r, 48_000);
        let at = (grid.origin + 1.5 * grid.frames_per_beat - 480.0).ceil() as usize;
        assert!(out[at - 2000..at].iter().all(|s| s.abs() < 1e-3));
        assert!(out[at..at + 400].iter().any(|s| s.abs() > 0.05));
    }

    #[test]
    fn clearing_drops_what_was_scheduled() {
        let (mut c, mut r) = engine(SR);
        c.send(Command::Metronome { on: true, bpm: 120.0 });
        run(&mut r, 480);
        let grid = c.shared.grid().unwrap();
        c.send(Command::Schedule { pad: 4, beat: 1.5 });
        c.send(Command::ClearScheduled);
        let out = run(&mut r, 48_000);
        let at = (grid.origin + 1.5 * grid.frames_per_beat - 480.0).ceil() as usize;
        assert!(out[at..at + 2000].iter().all(|s| s.abs() < 1e-3));
    }

    #[test]
    fn pounding_every_pad_never_overflows_or_clips() {
        let (mut c, mut r) = engine(SR);
        let mut peak = 0.0f32;
        for round in 0..200 {
            for pad in 0..8 {
                c.send(Command::Hit { pad, velocity: 1.0 });
            }
            c.send(Command::Hit { pad: 99, velocity: 1.0 }); // unknown pad: ignored
            if round % 50 == 0 {
                c.send(Command::Metronome { on: round % 100 == 0, bpm: 60.0 + round as f32 });
            }
            for s in run(&mut r, 480) {
                assert!(s.is_finite());
                peak = peak.max(s.abs());
            }
        }
        assert!(peak <= 1.0, "{peak}");
    }
}
