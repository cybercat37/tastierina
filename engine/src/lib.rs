//! Tastierina's audio engine.
//!
//! The UI talks to it only through [`Control`] (commands in, a few atomics
//! out); the audio callback owns the [`Renderer`]. Rendering never allocates,
//! locks or blocks: commands arrive through a fixed SPSC queue and voices come
//! from a fixed pool.

pub mod dsp;
pub mod perform;
pub mod repeat;
pub mod spsc;
pub mod timing;
pub mod voices;

use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;

use dsp::soft_clip;
use timing::BeatGrid;
use voices::{Kit, Pad, Sound, Timbre, Voice};

pub const MAX_VOICES: usize = 24;
/// Room for a whole song being sent at once (three giri of notes).
pub const QUEUE_LEN: usize = 1024;
/// A giro is 1, 2 or 4 bars of sixteen steps.
pub const MAX_STEPS: usize = 64;
/// Giri A, B and C (level 4).
pub const GIRI: usize = 3;
/// Steps of the scaletta at most.
pub const CHAIN_MAX: usize = 8;
const NO_CHAIN: u8 = 255;

/// One giro: its length, a bitmask of steps per drum sound, a key per step per note row.
#[derive(Clone, Copy)]
struct Giro {
    bars: u8,
    drums: [u64; DRUM_TRACKS],
    notes: [[i8; MAX_STEPS]; NOTE_ROWS],
}

impl Giro {
    const EMPTY: Giro = Giro { bars: 1, drums: [0; DRUM_TRACKS], notes: [[-1; MAX_STEPS]; NOTE_ROWS] };

    fn steps(&self) -> usize {
        self.bars as usize * DRUM_STEPS as usize
    }
}

/// Where the sequencer is, as the audio thread last published it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SeqState {
    pub running: bool,
    /// The next sixteenth to play, counted from beat 0 of the grid (wrapping).
    pub next_step: u32,
    /// The step of the giro that sixteenth will play.
    pub pos: u8,
    pub giro: u8,
    pub bars: u8,
    /// Step of the scaletta being played, 15 when it is not driving.
    pub chain_pos: u8,
    /// How many times that step has already played its giro.
    pub chain_rep: u8,
    /// A giro chosen by hand, waiting for the end of the loop.
    pub queued: Option<u8>,
}

impl SeqState {
    pub fn pack(&self) -> u64 {
        self.next_step as u64
            | (self.pos as u64) << 32
            | (self.giro as u64 & 3) << 40
            | (self.bars as u64 & 7) << 42
            | (self.chain_pos as u64 & 15) << 45
            | (self.chain_rep as u64 & 15) << 49
            | (self.running as u64) << 53
            | (self.queued.map_or(0, |q| q as u64 + 1) & 3) << 54
    }

    pub fn unpack(v: u64) -> SeqState {
        let queued = ((v >> 54) & 3) as u8;
        SeqState {
            next_step: v as u32,
            pos: (v >> 32) as u8,
            giro: ((v >> 40) & 3) as u8,
            bars: ((v >> 42) & 7) as u8,
            chain_pos: ((v >> 45) & 15) as u8,
            chain_rep: ((v >> 49) & 15) as u8,
            running: (v >> 53) & 1 == 1,
            queued: if queued == 0 { None } else { Some(queued - 1) },
        }
    }

    /// The step of the giro that the absolute sixteenth `abs_step` lands on.
    pub fn step_at(&self, abs_step: i64) -> usize {
        let total = (self.bars.max(1) as i64) * DRUM_STEPS;
        let ahead = (abs_step as u32).wrapping_sub(self.next_step) as i32 as i64;
        (self.pos as i64 + ahead).rem_euclid(total) as usize
    }
}
/// Sounds of the drum machine (level 2). Ids: 0 cassa, 1 rullante, 2 clap,
/// 3 hat chiuso, 4 hat aperto, 5 tom basso, 6 cowbell, then `Kit::ALL` from 7.
pub const DRUM_TRACKS: usize = 16;
/// Steps of a drum pattern: sixteenths of one 4/4 bar.
pub const DRUM_STEPS: i64 = 16;

/// The sound of a drum-machine row.
pub fn drum_sound(track: u8) -> Option<Sound> {
    match track {
        0 => Some(Sound::Pad(Pad::Kick)),
        1 => Some(Sound::Pad(Pad::Snare)),
        2 => Some(Sound::Pad(Pad::Clap)),
        3 => Some(Sound::Pad(Pad::Hat)),
        4 => Some(Sound::OpenHat),
        5 => Some(Sound::Pad(Pad::Tom)),
        6 => Some(Sound::Pad(Pad::Cowbell)),
        n => Kit::ALL.get(n as usize - 7).map(|&k| Sound::Kit(k)),
    }
}

/// Note rows of the sequencer (level 3): 0 is the bass, 1 the melody.
pub const NOTE_ROWS: usize = 2;
/// MIDI note of key 0 on each note row.
pub const ROW_BASE: [u8; NOTE_ROWS] = [36, 60];
pub const ROW_TIMBRE: [Timbre; NOTE_ROWS] = [Timbre::Bass, Timbre::Lead];
/// How much of its step a note row's note is held.
const ROW_GATE: [f64; NOTE_ROWS] = [0.55, 0.8];

/// Cutoff of the bass filter for a setting 0..1: 120 Hz closed, 7.7 kHz open.
pub fn bass_cutoff(setting: f32) -> f32 {
    120.0 * 2f32.powf(setting.clamp(0.0, 1.0) * 6.0)
}

/// Events waiting for a future beat: enough for a whole song's notes and releases.
pub const MAX_SCHEDULED: usize = 160;

/// Something to do at a beat of the metronome grid.
#[derive(Clone, Copy, Debug, PartialEq)]
enum Event {
    Pad(u8),
    Count(bool),
    NoteOn(u8, Timbre),
    NoteOff(u8),
    /// End of a sequencer note: releases only that row's voice.
    SeqOff(u8, Timbre),
}
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
    /// Press a keyboard key: `midi` sounds with `timbre` until `NoteOff`.
    NoteOn { midi: u8, timbre: u8, velocity: f32 },
    /// Let go of a keyboard key.
    NoteOff { midi: u8 },
    /// Let go of every keyboard key.
    AllNotesOff,
    /// A keyboard note at a beat, held for `length` beats. Same rules as `Schedule`.
    ScheduleNote { midi: u8, timbre: u8, beat: f64, length: f64 },
    /// Whether the metronome ticks are heard (the grid keeps running either way).
    Click(bool),
    /// Turn one step of a drum row on or off.
    SetStep { giro: u8, track: u8, step: u8, on: bool },
    /// A whole drum row at once: bit `s` is step `s`.
    SetPattern { giro: u8, track: u8, steps: u64 },
    /// Start or stop the drum machine; it plays on the metronome grid, a step per sixteenth.
    Sequencer(bool),
    /// Play one drum row's sound now (to hear a step when it is turned on).
    Drum { track: u8 },
    /// Put a key (0..18, or -1 for silence) on a step of a note row.
    SetNote { giro: u8, row: u8, step: u8, key: i8 },
    /// Empty a giro: no drum steps, no notes. Its length stays.
    ClearGiro { giro: u8 },
    /// Length of a giro: 1, 2 or 4 bars.
    SetBars { giro: u8, bars: u8 },
    /// Play (and edit) this giro: at once when stopped, at the end of the loop when playing.
    SelectGiro { giro: u8 },
    /// Step `index` of the scaletta: play `giro` `reps` times.
    SetChain { index: u8, giro: u8, reps: u8 },
    /// Length of the scaletta, and whether it drives the giri.
    Chain { len: u8, on: bool },
    /// How much of its step a bass note is held, 0.15..1.
    BassGate(f32),
    /// A key played live while recording: `abs_step` counts sixteenths from beat 0.
    /// If that step has not sounded yet it is skipped once, since the player just played it.
    RecordNote { row: u8, abs_step: i64, key: i8 },
    /// Bass filter, 0 (dark) to 1 (bright).
    BassFilter(f32),
    /// Forget everything scheduled.
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
    /// `SeqState::pack`.
    seq: AtomicU64,
}

impl Shared {
    pub fn frames_rendered(&self) -> u64 {
        self.frames_rendered.load(Ordering::Acquire)
    }

    pub fn sequencer(&self) -> SeqState {
        SeqState::unpack(self.seq.load(Ordering::Acquire))
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
    /// (event, beat) slots; `None` is free.
    scheduled: [Option<(Event, f64)>; MAX_SCHEDULED],
    scheduled_count: usize,
    /// Earliest scheduled beat, so the per-sample check is one comparison.
    next_due: f64,
    click: bool,
    giri: [Giro; GIRI],
    /// The giro being played and edited.
    cur: usize,
    queued: Option<u8>,
    /// The step of the current giro that `next_step` will play.
    pos: usize,
    /// True until the first step after a start: that loop start changes nothing.
    fresh: bool,
    chain: [(u8, u8); CHAIN_MAX],
    chain_len: u8,
    chain_on: bool,
    chain_pos: u8,
    chain_rep: u8,
    bass_gate: f32,
    sequencer: bool,
    /// Next sixteenth to play, counted from beat 0 of the grid.
    next_step: i64,
    skip: [[bool; MAX_STEPS]; NOTE_ROWS],
    bass_filter: f32,
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
        seq: AtomicU64::new(0),
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
        next_due: f64::INFINITY,
        click: true,
        giri: [Giro::EMPTY; GIRI],
        cur: 0,
        queued: None,
        pos: 0,
        fresh: false,
        chain: [(0, 1); CHAIN_MAX],
        chain_len: 0,
        chain_on: false,
        chain_pos: NO_CHAIN,
        chain_rep: 0,
        bass_gate: 0.55,
        sequencer: false,
        next_step: 0,
        skip: [[false; MAX_STEPS]; NOTE_ROWS],
        bass_filter: 0.45,
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
                    if self.click {
                        self.start(Sound::Tick(accent), 1.0);
                    }
                    self.metronome.next_beat += 1;
                }
                if self.sequencer {
                    let due = self.metronome.grid.origin + self.metronome.grid.frames_per_beat * self.next_step as f64 / 4.0;
                    if self.frame as f64 >= due {
                        self.play_step();
                    }
                }
                if self.scheduled_count > 0 && self.metronome.grid.position(self.frame as f64) >= self.next_due {
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
        self.shared.seq.store(self.seq_state().pack(), Ordering::Release);
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
                self.sequencer = false;
                self.clear_scheduled();
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
                    self.schedule(Event::Pad(pad), beat);
                }
            }
            Command::ScheduleCount { beat, go } => {
                self.schedule(Event::Count(go), beat);
            }
            Command::ScheduleNote { midi, timbre, beat, length } => {
                if self.schedule(Event::NoteOn(midi, Timbre::from_index(timbre)), beat) {
                    self.schedule(Event::NoteOff(midi), beat + length.max(0.05));
                }
            }
            Command::ClearScheduled => self.clear_scheduled(),
            Command::NoteOn { midi, timbre, velocity } => self.note_on(midi, Timbre::from_index(timbre), velocity),
            Command::NoteOff { midi } => self.note_off(midi),
            Command::AllNotesOff => {
                for v in self.voices.iter_mut() {
                    v.release();
                }
            }
            Command::Click(on) => self.click = on,
            Command::SetStep { giro, track, step, on } => {
                if (giro as usize) < GIRI && (track as usize) < DRUM_TRACKS && (step as usize) < MAX_STEPS {
                    let bit = 1u64 << step;
                    let row = &mut self.giri[giro as usize].drums[track as usize];
                    *row = if on { *row | bit } else { *row & !bit };
                }
            }
            Command::SetPattern { giro, track, steps } => {
                if (giro as usize) < GIRI && (track as usize) < DRUM_TRACKS {
                    self.giri[giro as usize].drums[track as usize] = steps;
                }
            }
            Command::SetNote { giro, row, step, key } => {
                if (giro as usize) < GIRI && (row as usize) < NOTE_ROWS && (step as usize) < MAX_STEPS {
                    self.giri[giro as usize].notes[row as usize][step as usize] = key.clamp(-1, 17);
                }
            }
            Command::ClearGiro { giro } => {
                if let Some(g) = self.giri.get_mut(giro as usize) {
                    g.drums = [0; DRUM_TRACKS];
                    g.notes = [[-1; MAX_STEPS]; NOTE_ROWS];
                }
            }
            Command::SetBars { giro, bars } => {
                if (giro as usize) < GIRI && matches!(bars, 1 | 2 | 4) {
                    self.giri[giro as usize].bars = bars;
                }
            }
            Command::SelectGiro { giro } => {
                if (giro as usize) < GIRI {
                    if !self.sequencer {
                        self.cur = giro as usize;
                        self.pos = 0;
                    } else if !self.chain_live() {
                        self.queued = if giro as usize == self.cur { None } else { Some(giro) };
                    }
                }
            }
            Command::SetChain { index, giro, reps } => {
                if (index as usize) < CHAIN_MAX && (giro as usize) < GIRI {
                    self.chain[index as usize] = (giro, reps.clamp(1, 8));
                }
            }
            Command::Chain { len, on } => {
                self.chain_len = len.min(CHAIN_MAX as u8);
                if on != self.chain_on {
                    // Turned on or off while playing: it takes over, or lets go, at the next loop.
                    self.chain_on = on;
                    self.queued = None;
                    self.chain_pos = NO_CHAIN;
                    self.chain_rep = 0;
                }
                if self.chain_pos >= self.chain_len {
                    self.chain_pos = NO_CHAIN;
                }
            }
            Command::BassGate(v) => self.bass_gate = v.clamp(0.15, 1.0),
            Command::RecordNote { row, abs_step, key } => {
                if (row as usize) < NOTE_ROWS {
                    let total = self.giri[self.cur].steps() as i64;
                    let step = if self.sequencer {
                        (self.pos as i64 + abs_step - self.next_step).rem_euclid(total) as usize
                    } else {
                        abs_step.rem_euclid(total) as usize
                    };
                    self.giri[self.cur].notes[row as usize][step] = key.clamp(-1, 17);
                    if self.sequencer && abs_step >= self.next_step {
                        self.skip[row as usize][step] = true;
                    }
                }
            }
            Command::BassFilter(v) => self.bass_filter = v.clamp(0.0, 1.0),
            Command::Sequencer(on) => {
                self.sequencer = on && self.metronome.on;
                self.skip = [[false; MAX_STEPS]; NOTE_ROWS];
                self.queued = None;
                if !self.sequencer {
                    self.release_rows();
                }
                if self.sequencer {
                    let pos = self.metronome.grid.position(self.frame as f64);
                    self.next_step = (pos * 4.0).ceil().max(0.0) as i64;
                    self.pos = 0;
                    self.fresh = true;
                    if self.chain_live() {
                        // The scaletta always starts from its first step.
                        self.chain_pos = 0;
                        self.chain_rep = 0;
                        self.cur = self.chain[0].0 as usize;
                    }
                }
            }
            Command::Drum { track } => {
                if let Some(sound) = drum_sound(track) {
                    self.start(sound, 1.0);
                }
            }
        }
    }

    fn chain_live(&self) -> bool {
        self.chain_on && self.chain_len > 0
    }

    fn seq_state(&self) -> SeqState {
        SeqState {
            running: self.sequencer,
            next_step: self.next_step as u32,
            pos: self.pos as u8,
            giro: self.cur as u8,
            bars: self.giri[self.cur].bars,
            chain_pos: if self.chain_live() && self.chain_pos < self.chain_len { self.chain_pos } else { 15 },
            chain_rep: self.chain_rep,
            queued: self.queued,
        }
    }

    /// Plays the step that is due and moves on. Giri change only here, on step 0.
    fn play_step(&mut self) {
        if self.pos >= self.giri[self.cur].steps() {
            self.pos = 0; // the giro got shorter under the playhead
        }
        if self.pos == 0 {
            self.loop_start();
        }
        let giro = self.giri[self.cur];
        let step = self.pos;
        for track in 0..DRUM_TRACKS {
            if giro.drums[track] >> step & 1 != 0 {
                if let Some(sound) = drum_sound(track as u8) {
                    self.start(sound, 1.0);
                }
            }
        }
        for row in 0..NOTE_ROWS {
            let key = giro.notes[row][step];
            if self.skip[row][step] {
                self.skip[row][step] = false;
            } else if key >= 0 {
                let midi = ROW_BASE[row] + key as u8;
                let gate = if row == 0 { self.bass_gate as f64 } else { ROW_GATE[row] };
                self.note_on(midi, ROW_TIMBRE[row], 0.9);
                self.schedule(Event::SeqOff(midi, ROW_TIMBRE[row]), (self.next_step as f64 + gate) / 4.0);
            }
        }
        self.pos = (step + 1) % giro.steps();
        self.next_step += 1;
    }

    /// The loop is about to start again: the scaletta moves on, or a giro chosen by hand comes in.
    fn loop_start(&mut self) {
        let before = self.cur;
        if self.fresh {
            self.fresh = false;
        } else if self.chain_live() {
            if self.chain_pos >= self.chain_len {
                self.chain_pos = 0;
                self.chain_rep = 0;
            } else {
                self.chain_rep += 1;
                if self.chain_rep >= self.chain[self.chain_pos as usize].1 {
                    self.chain_pos = (self.chain_pos + 1) % self.chain_len;
                    self.chain_rep = 0;
                }
            }
            self.cur = self.chain[self.chain_pos as usize].0 as usize;
        }
        if !self.chain_live() {
            if let Some(q) = self.queued.take() {
                self.cur = q as usize;
            }
        }
        if self.cur != before {
            self.skip = [[false; MAX_STEPS]; NOTE_ROWS];
        }
    }

    fn clear_scheduled(&mut self) {
        self.scheduled = [None; MAX_SCHEDULED];
        self.scheduled_count = 0;
        self.next_due = f64::INFINITY;
        // The pending ends of sequencer notes went too: let those notes go now.
        self.release_rows();
    }

    fn release_rows(&mut self) {
        for v in self.voices.iter_mut() {
            if matches!(v.note_timbre(), Some(Timbre::Bass | Timbre::Lead)) {
                v.release();
            }
        }
    }

    /// Queues an event; false when it cannot happen (no metronome, past, or full).
    fn schedule(&mut self, event: Event, beat: f64) -> bool {
        let past = self.metronome.on && self.metronome.grid.position(self.frame as f64) > beat;
        if !self.metronome.on || past {
            return false;
        }
        match self.scheduled.iter_mut().find(|s| s.is_none()) {
            Some(slot) => {
                *slot = Some((event, beat));
                self.scheduled_count += 1;
                self.next_due = self.next_due.min(beat);
                true
            }
            None => false,
        }
    }

    fn play_due(&mut self) {
        let pos = self.metronome.grid.position(self.frame as f64);
        let mut next = f64::INFINITY;
        for i in 0..MAX_SCHEDULED {
            if let Some((event, beat)) = self.scheduled[i] {
                if pos < beat {
                    next = next.min(beat);
                    continue;
                }
                self.scheduled[i] = None;
                self.scheduled_count -= 1;
                match event {
                    Event::Pad(pad) => {
                        if let Some(p) = Pad::from_index(pad) {
                            self.start(Sound::Pad(p), 1.0);
                        }
                    }
                    Event::Count(go) => self.start(Sound::Count(go), 1.0),
                    Event::NoteOn(midi, timbre) => self.note_on(midi, timbre, 0.9),
                    Event::NoteOff(midi) => self.note_off(midi),
                    Event::SeqOff(midi, timbre) => self.release(midi, Some(timbre)),
                }
            }
        }
        self.next_due = next;
    }

    fn note_on(&mut self, midi: u8, timbre: Timbre, velocity: f32) {
        // Pressing a held key again restarts it rather than stacking voices.
        self.release(midi, Some(timbre));
        let voice = self.start_voice(Sound::Note(timbre, midi.min(127)), velocity);
        if timbre == Timbre::Bass {
            let cutoff = bass_cutoff(self.bass_filter);
            self.voices[voice].set_sweep((cutoff * 4.0).min(12_000.0), cutoff, 0.08, 4.0);
        }
    }

    fn note_off(&mut self, midi: u8) {
        self.release(midi, None);
    }

    /// Lets go of the held voices on `midi`: of one timbre, or of any.
    fn release(&mut self, midi: u8, timbre: Option<Timbre>) {
        for v in self.voices.iter_mut() {
            if v.held_note() == Some(midi) && (timbre.is_none() || v.note_timbre() == timbre) {
                v.release();
            }
        }
    }

    fn start(&mut self, sound: Sound, velocity: f32) {
        self.start_voice(sound, velocity);
    }

    /// Starts a sound and says which voice took it.
    fn start_voice(&mut self, sound: Sound, velocity: f32) -> usize {
        // A free voice, else steal the oldest.
        let idx = self
            .voices
            .iter()
            .position(|v| !v.is_active())
            .or_else(|| self.voices.iter().position(|v| v.is_released()))
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
        idx
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
    fn a_held_note_sounds_until_released() {
        let (mut c, mut r) = engine(SR);
        c.send(Command::NoteOn { midi: 60, timbre: 1, velocity: 1.0 });
        let held = run(&mut r, 24_000);
        assert!(held[20_000..].iter().any(|s| s.abs() > 0.05), "organ holds");
        c.send(Command::NoteOff { midi: 60 });
        let after = run(&mut r, 24_000);
        assert!(after[12_000..].iter().all(|s| s.abs() < 1e-3), "and stops");
    }

    #[test]
    fn many_keys_at_once_and_all_notes_off() {
        let (mut c, mut r) = engine(SR);
        for midi in 48..66 {
            c.send(Command::NoteOn { midi, timbre: 2, velocity: 1.0 });
        }
        let out = run(&mut r, 9600);
        assert!(out.iter().all(|s| s.is_finite() && s.abs() <= 1.0));
        c.send(Command::AllNotesOff);
        let after = run(&mut r, 48_000);
        assert!(after[36_000..].iter().all(|s| s.abs() < 1e-3));
    }

    #[test]
    fn scheduled_notes_start_and_stop_on_their_beats() {
        let (mut c, mut r) = engine(SR);
        c.send(Command::Metronome { on: true, bpm: 120.0 });
        c.send(Command::Click(false));
        run(&mut r, 480);
        let grid = c.shared.grid().unwrap();
        c.send(Command::ScheduleNote { midi: 67, timbre: 1, beat: 1.0, length: 1.0 });
        let out = run(&mut r, 48_000 * 2);
        let frame = |beat: f64| (grid.origin + beat * grid.frames_per_beat - 480.0).ceil() as usize;
        assert!(out[..frame(1.0)].iter().all(|s| s.abs() < 1e-3), "silent before, no click");
        assert!(out[frame(1.0)..frame(1.9)].iter().any(|s| s.abs() > 0.05), "sounds for its length");
        assert!(out[frame(2.6)..frame(3.0)].iter().all(|s| s.abs() < 1e-3), "released after");
    }

    /// Where the output turns audible after a silence, as sixteenth steps at 120 BPM from `origin`.
    fn onsets(out: &[f32], origin: f64) -> Vec<i64> {
        let step = 6000.0;
        let mut found = Vec::new();
        let mut quiet = 0usize;
        for (i, s) in out.iter().enumerate() {
            if s.abs() > 0.02 {
                if quiet > 1500 {
                    found.push(((i as f64 - origin) / step).round() as i64);
                }
                quiet = 0;
            } else {
                quiet += 1;
            }
        }
        found
    }

    #[test]
    fn the_drum_machine_plays_its_steps_on_the_grid() {
        let (mut c, mut r) = engine(SR);
        c.send(Command::Metronome { on: true, bpm: 120.0 });
        c.send(Command::Click(false));
        // Kick on steps 0 and 8, hat on step 4.
        c.send(Command::SetPattern { giro: 0, track: 0, steps: 0b0000_0001_0000_0001 });
        c.send(Command::SetStep { giro: 0, track: 3, step: 4, on: true });
        c.send(Command::Sequencer(true));
        let out = run(&mut r, 48_000 * 4);
        let grid = c.shared.grid().unwrap();
        let hits = onsets(&out, grid.origin);
        assert_eq!(&hits[..6], &[0, 4, 8, 16, 20, 24], "two bars of the pattern: {hits:?}");
    }

    #[test]
    fn steps_change_while_playing_and_stop_stops() {
        let (mut c, mut r) = engine(SR);
        c.send(Command::Metronome { on: true, bpm: 120.0 });
        c.send(Command::Click(false));
        c.send(Command::SetStep { giro: 0, track: 4, step: 0, on: true });
        c.send(Command::Sequencer(true));
        run(&mut r, 48_000); // half a bar at 120 BPM: the open hat on step 0 has played
        c.send(Command::SetStep { giro: 0, track: 4, step: 0, on: false });
        c.send(Command::SetStep { giro: 0, track: 1, step: 8, on: true });
        let out = run(&mut r, 48_000);
        let grid = c.shared.grid().unwrap();
        let hits = onsets(&out, grid.origin - 48_000.0);
        assert_eq!(hits, vec![8], "the new snare on step 8, nothing else: {hits:?}");
        c.send(Command::Sequencer(false));
        let after = run(&mut r, 48_000 * 2);
        assert!(after[24_000..].iter().all(|s| s.abs() < 1e-3));
    }

    #[test]
    fn note_rows_play_on_their_step_and_let_go() {
        let (mut c, mut r) = engine(SR);
        c.send(Command::Metronome { on: true, bpm: 120.0 });
        c.send(Command::Click(false));
        c.send(Command::SetNote { giro: 0, row: 0, step: 4, key: 9 });
        c.send(Command::SetNote { giro: 0, row: 1, step: 12, key: 4 });
        c.send(Command::Sequencer(true));
        let out = run(&mut r, 48_000 * 2);
        let grid = c.shared.grid().unwrap();
        assert_eq!(onsets(&out, grid.origin), vec![4, 12], "bass on step 4, melody on step 12");
        // The bass is held about half a step, then released: silent well before the melody.
        let at = |step: f64| (grid.origin + step * 6000.0) as usize;
        assert!(out[at(7.0)..at(11.5)].iter().all(|s| s.abs() < 1e-3));
        c.send(Command::Sequencer(false));
        let after = run(&mut r, 48_000);
        assert!(after[12_000..].iter().all(|s| s.abs() < 1e-3), "nothing left hanging");
    }

    #[test]
    fn a_recorded_note_is_not_doubled_but_plays_from_the_next_loop() {
        let (mut c, mut r) = engine(SR);
        c.send(Command::Metronome { on: true, bpm: 120.0 });
        c.send(Command::Click(false));
        c.send(Command::Sequencer(true));
        // Played live just before step 8: the sequencer must not play it again this time round.
        c.send(Command::RecordNote { row: 0, abs_step: 8, key: 0 });
        // Played live after step 2 had already gone by: nothing to skip.
        run(&mut r, 24_000);
        c.send(Command::RecordNote { row: 0, abs_step: 2, key: 0 });
        let out = [run(&mut r, 24_000), run(&mut r, 48_000 * 3)].concat();
        let grid = c.shared.grid().unwrap();
        let hits = onsets(&out, grid.origin - 24_000.0);
        assert_eq!(&hits[..2], &[18, 24], "silent at step 8, then steps 2 and 8 of the next bar: {hits:?}");
    }

    #[test]
    fn the_bass_filter_makes_the_bass_brighter() {
        let brightness = |setting: f32| {
            let (mut c, mut r) = engine(SR);
            c.send(Command::BassFilter(setting));
            c.send(Command::NoteOn { midi: 45, timbre: 9, velocity: 1.0 });
            let out = run(&mut r, 24_000);
            assert!(out.iter().all(|s| s.is_finite() && s.abs() <= 1.0));
            out.windows(2).map(|w| (w[1] - w[0]).abs()).sum::<f32>()
        };
        assert!(brightness(1.0) > brightness(0.0) * 2.0);
        assert!((bass_cutoff(0.0) - 120.0).abs() < 0.01 && (bass_cutoff(1.0) - 7680.0).abs() < 1.0);
    }

    fn kick(giro: u8, step: u8) -> Command {
        Command::SetStep { giro, track: 0, step, on: true }
    }

    fn start_sequencer(c: &mut Control) {
        c.send(Command::Metronome { on: true, bpm: 120.0 });
        c.send(Command::Click(false));
        c.send(Command::Sequencer(true));
    }

    #[test]
    fn a_giro_can_be_two_bars_long() {
        let (mut c, mut r) = engine(SR);
        c.send(Command::SetBars { giro: 0, bars: 2 });
        c.send(kick(0, 0));
        c.send(kick(0, 20));
        start_sequencer(&mut c);
        let out = run(&mut r, 48_000 * 9);
        let grid = c.shared.grid().unwrap();
        assert_eq!(&onsets(&out, grid.origin)[..4], &[0, 20, 32, 52]);
    }

    #[test]
    fn a_giro_chosen_while_playing_comes_in_at_the_end_of_the_loop() {
        let (mut c, mut r) = engine(SR);
        c.send(kick(0, 0));
        c.send(kick(1, 4));
        start_sequencer(&mut c);
        let first = run(&mut r, 24_000);
        c.send(Command::SelectGiro { giro: 1 });
        run(&mut r, 480);
        assert_eq!(c.shared.sequencer().queued, Some(1));
        assert_eq!(c.shared.sequencer().giro, 0, "A plays to the end of its bar");
        let rest = run(&mut r, 48_000 * 5);
        let grid = c.shared.grid().unwrap();
        let out = [first, vec![0.0; 480], rest].concat();
        assert_eq!(&onsets(&out, grid.origin)[..3], &[0, 20, 36], "A once, then B on its step 4");
        let state = c.shared.sequencer();
        assert!(state.running && state.giro == 1 && state.queued.is_none());
    }

    #[test]
    fn the_scaletta_drives_the_giri_and_starts_over() {
        let (mut c, mut r) = engine(SR);
        c.send(kick(0, 0));
        c.send(kick(1, 8));
        c.send(Command::SetChain { index: 0, giro: 0, reps: 2 });
        c.send(Command::SetChain { index: 1, giro: 1, reps: 1 });
        c.send(Command::Chain { len: 2, on: true });
        c.send(Command::SelectGiro { giro: 1 }); // stopped on B: the scaletta still starts from its first step
        start_sequencer(&mut c);
        let out = run(&mut r, 48_000 * 13);
        let grid = c.shared.grid().unwrap();
        assert_eq!(&onsets(&out, grid.origin)[..6], &[0, 16, 40, 48, 64, 88], "A A B, A A B");
        // While it drives, a giro picked by hand is ignored.
        c.send(Command::SelectGiro { giro: 2 });
        run(&mut r, 480);
        assert_eq!(c.shared.sequencer().queued, None);
    }

    #[test]
    fn sequencer_state_packs_and_finds_steps() {
        let s = SeqState { running: true, next_step: 70, pos: 6, giro: 2, bars: 4, chain_pos: 3, chain_rep: 7, queued: Some(1) };
        assert_eq!(SeqState::unpack(s.pack()), s);
        let idle = SeqState { running: false, next_step: 0, pos: 0, giro: 0, bars: 1, chain_pos: 15, chain_rep: 0, queued: None };
        assert_eq!(SeqState::unpack(idle.pack()), idle);
        assert_eq!(s.step_at(70), 6);
        assert_eq!(s.step_at(68), 4, "two sixteenths ago");
        assert_eq!(s.step_at(60), 60, "before the loop start it wraps to the end of the giro");
    }

    #[test]
    fn the_bass_gate_holds_notes_longer() {
        let energy = |gate: f32| {
            let (mut c, mut r) = engine(SR);
            c.send(Command::BassGate(gate));
            c.send(Command::SetNote { giro: 0, row: 0, step: 0, key: 9 });
            start_sequencer(&mut c);
            run(&mut r, 48_000).iter().map(|s| s * s).sum::<f32>()
        };
        assert!(energy(1.0) > energy(0.2) * 2.0);
    }

    #[test]
    fn clearing_a_giro_leaves_the_others() {
        let (mut c, mut r) = engine(SR);
        c.send(kick(0, 0));
        c.send(kick(1, 0));
        c.send(Command::ClearGiro { giro: 0 });
        start_sequencer(&mut c);
        assert!(run(&mut r, 48_000 * 2).iter().all(|s| s.abs() < 1e-3), "A is empty");
        c.send(Command::SelectGiro { giro: 1 });
        assert!(run(&mut r, 48_000 * 2).iter().any(|s| s.abs() > 0.1), "B still has its kick");
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
