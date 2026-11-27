//! The sounds. Every sound is synthesised, so the app needs no sample files.
//!
//! A `Voice` is a small fixed-size state machine; the renderer keeps a fixed
//! pool of them and never allocates.

use crate::dsp::{
    decay_coef, glide_coef, saw_turns, sin_turns, square_turns, triangle_turns, Biquad, FilterKind, Noise,
};

/// The eight pads of level 0, in screen order.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum Pad {
    Kick = 0,
    Snare = 1,
    Clap = 2,
    Hat = 3,
    Tom = 4,
    Cowbell = 5,
    Bass = 6,
    Chord = 7,
}

impl Pad {
    pub const ALL: [Pad; 8] = [
        Pad::Kick,
        Pad::Snare,
        Pad::Clap,
        Pad::Hat,
        Pad::Tom,
        Pad::Cowbell,
        Pad::Bass,
        Pad::Chord,
    ];

    pub fn from_index(i: u8) -> Option<Pad> {
        Pad::ALL.get(i as usize).copied()
    }

    pub fn name(self) -> &'static str {
        match self {
            Pad::Kick => "cassa",
            Pad::Snare => "rullante",
            Pad::Clap => "clap",
            Pad::Hat => "hat",
            Pad::Tom => "tom",
            Pad::Cowbell => "cowbell",
            Pad::Bass => "basso",
            Pad::Chord => "accordo",
        }
    }
}

/// The sounds of the keyboard (level 1).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum Timbre {
    Piano = 0,
    Organ = 1,
    Synth = 2,
    Bell = 3,
    Strings = 4,
    Flute = 5,
    Guitar = 6,
    Marimba = 7,
    ElectricPiano = 8,
}

impl Timbre {
    pub const ALL: [Timbre; 9] = [
        Timbre::Piano,
        Timbre::Organ,
        Timbre::Synth,
        Timbre::Bell,
        Timbre::Strings,
        Timbre::Flute,
        Timbre::Guitar,
        Timbre::Marimba,
        Timbre::ElectricPiano,
    ];

    pub fn from_index(i: u8) -> Timbre {
        Timbre::ALL.get(i as usize).copied().unwrap_or(Timbre::Piano)
    }

    pub fn name(self) -> &'static str {
        match self {
            Timbre::Piano => "piano",
            Timbre::Organ => "organo",
            Timbre::Synth => "synth",
            Timbre::Bell => "campana",
            Timbre::Strings => "archi",
            Timbre::Flute => "flauto",
            Timbre::Guitar => "chitarra",
            Timbre::Marimba => "marimba",
            Timbre::ElectricPiano => "piano-elettrico",
        }
    }
}

/// Frequency of a MIDI note (69 = A4 = 440 Hz).
pub fn midi_freq(midi: u8) -> f32 {
    440.0 * 2f32.powf((midi as f32 - 69.0) / 12.0)
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Stage {
    Attack,
    Decay,
    Release,
}

/// What a voice is playing: a pad, a held note, or the metronome tick.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Sound {
    /// A keyboard note: sounds until released (or until it dies away, for piano and bell).
    Note(Timbre, u8),
    Pad(Pad),
    /// `true` for the first beat of the bar.
    Tick(bool),
    /// Count-in before the player's turn; `true` for the last count ("1": go).
    Count(bool),
    /// The open hi-hat of the drum machine (level 2).
    OpenHat,
}

#[derive(Clone, Copy, Debug)]
pub struct Voice {
    pub sound: Sound,
    active: bool,
    /// Samples since the trigger.
    t: u32,
    /// Hard stop, in samples.
    len: u32,
    gain: f32,
    gain_k: f32,
    /// Second envelope (tones under noise, filter sweeps).
    env2: f32,
    env2_k: f32,
    phase: [f32; 4],
    /// Phase increment per sample, in turns.
    inc: [f32; 4],
    /// Pitch glide: current and target frequency, glide multiplier.
    freq: f32,
    freq_end: f32,
    freq_k: f32,
    noise: Noise,
    /// Per-partial level, the level it settles at, and the glide toward it (notes only).
    partial: [f32; 4],
    partial_floor: [f32; 4],
    partial_k: [f32; 4],
    midi: u8,
    stage: Stage,
    attack: u32,
    peak: f32,
    sustain: f32,
    decay_k: f32,
    release_k: f32,
    /// Lower notes a little louder, higher a little softer.
    track: f32,
    filter: Biquad,
    filter2: Biquad,
    sample_rate: f32,
}

impl Voice {
    pub fn silent() -> Self {
        Voice {
            sound: Sound::Tick(false),
            active: false,
            t: 0,
            len: 0,
            gain: 0.0,
            gain_k: 0.0,
            env2: 0.0,
            env2_k: 0.0,
            phase: [0.0; 4],
            partial: [0.0; 4],
            partial_floor: [0.0; 4],
            partial_k: [1.0; 4],
            midi: 0,
            stage: Stage::Attack,
            attack: 0,
            peak: 0.0,
            sustain: 0.0,
            decay_k: 0.0,
            release_k: 0.0,
            track: 1.0,
            inc: [0.0; 4],
            freq: 0.0,
            freq_end: 0.0,
            freq_k: 0.0,
            noise: Noise::new(1),
            filter: Biquad::default(),
            filter2: Biquad::default(),
            sample_rate: 48_000.0,
        }
    }

    pub fn is_active(&self) -> bool {
        self.active
    }

    /// Samples since the voice started; used to steal the oldest one.
    pub fn age(&self) -> u32 {
        self.t
    }

    /// Starts `sound` at `velocity` (0..1), replacing whatever this voice held.
    pub fn trigger(&mut self, sound: Sound, velocity: f32, sample_rate: f32, seed: u32) {
        let sr = sample_rate;
        let v = velocity.clamp(0.0, 1.0);
        *self = Voice::silent();
        self.sound = sound;
        self.active = true;
        self.sample_rate = sr;
        self.noise = Noise::new(seed);
        let secs = |s: f32| (s * sr) as u32;
        match sound {
            Sound::Note(timbre, midi) => {
                let f = midi_freq(midi);
                self.midi = midi;
                self.track = (261.63 / f).powf(0.25).min(1.3);
                self.stage = Stage::Attack;
                // (attack s, peak, decay time constant s, sustain, release time constant s)
                let (a, peak, dtc, sus, rtc) = match timbre {
                    Timbre::Piano => (0.004, 0.55, 1.1, 0.0, 0.09),
                    Timbre::Organ => (0.012, 0.30, 0.2, 0.30, 0.03),
                    Timbre::Synth => (0.010, 0.30, 0.4, 0.20, 0.08),
                    Timbre::Bell => (0.002, 0.50, 0.9, 0.0, 0.35),
                    // A slow bow, then a long sustain.
                    Timbre::Strings => (0.15, 0.55, 0.5, 0.45, 0.35),
                    Timbre::Flute => (0.06, 0.40, 0.3, 0.34, 0.12),
                    // Plucked and struck: no sustain, they ring and fade.
                    Timbre::Guitar => (0.002, 0.60, 1.4, 0.0, 0.2),
                    Timbre::Marimba => (0.001, 0.60, 0.45, 0.0, 0.15),
                    Timbre::ElectricPiano => (0.003, 0.50, 1.4, 0.0, 0.25),
                };
                self.attack = ((a * sr) as u32).max(1);
                self.peak = peak * v;
                self.sustain = sus * v;
                self.decay_k = glide_coef(sr, dtc);
                self.release_k = glide_coef(sr, rtc);
                // (frequency ratio, level, decay time constant s or 0 = steady, floor)
                let partials: [(f32, f32, f32, f32); 4] = match timbre {
                    Timbre::Piano => [(1.0, 1.0, 0.0, 1.0), (2.0, 0.45, 0.18, 0.06), (4.0, 0.18, 0.06, 0.0), (0.0, 0.0, 0.0, 0.0)],
                    Timbre::Organ => [(1.0, 1.0, 0.0, 1.0), (2.0, 0.55, 0.0, 0.55), (3.0, 0.3, 0.0, 0.3), (4.0, 0.14, 0.0, 0.14)],
                    Timbre::Synth => [(0.9954, 0.6, 0.0, 0.6), (1.0046, 0.6, 0.0, 0.6), (0.5, 0.25, 0.0, 0.25), (0.0, 0.0, 0.0, 0.0)],
                    Timbre::Bell => [(1.0, 1.0, 0.0, 1.0), (2.76, 0.5, 0.5, 0.0), (5.4, 0.25, 0.18, 0.0), (0.0, 0.0, 0.0, 0.0)],
                    // Three slightly detuned saws: an ensemble.
                    Timbre::Strings => [(0.997, 0.45, 0.0, 0.45), (1.003, 0.45, 0.0, 0.45), (2.0, 0.12, 0.0, 0.12), (0.0, 0.0, 0.0, 0.0)],
                    // Nearly pure, plus breath (partial 3 is noise).
                    Timbre::Flute => [(1.0, 1.0, 0.0, 1.0), (2.0, 0.12, 0.0, 0.12), (3.0, 0.05, 0.0, 0.05), (0.0, 0.05, 0.08, 0.015)],
                    // Upper harmonics die first, like a string.
                    Timbre::Guitar => [(1.0, 1.0, 1.2, 0.0), (2.0, 0.55, 0.6, 0.0), (3.0, 0.35, 0.3, 0.0), (4.0, 0.22, 0.15, 0.0)],
                    // A bar: the fundamental and a quick, high overtone.
                    Timbre::Marimba => [(1.0, 1.0, 0.4, 0.0), (4.0, 0.35, 0.05, 0.0), (9.9, 0.1, 0.02, 0.0), (0.0, 0.0, 0.0, 0.0)],
                    // FM: partial 1 modulates partial 0, its level is the index; partial 2 is the tine.
                    Timbre::ElectricPiano => [(1.0, 1.0, 0.0, 1.0), (1.0, 1.4, 0.35, 0.25), (14.0, 0.06, 0.02, 0.0), (0.0, 0.0, 0.0, 0.0)],
                };
                for (i, (ratio, level, tc, floor)) in partials.into_iter().enumerate() {
                    self.inc[i] = (f * ratio / sr).min(0.45);
                    self.partial[i] = level;
                    self.partial_floor[i] = floor;
                    self.partial_k[i] = if tc > 0.0 { glide_coef(sr, tc) } else { 1.0 };
                }
                // Filter cutoff, gliding from bright to darker.
                let sweep = match timbre {
                    Timbre::Synth => Some((7.0, 2.2, 0.22, 5.0)),
                    Timbre::Guitar => Some((12.0, 3.0, 0.25, 0.9)),
                    Timbre::Strings => Some((5.0, 4.0, 0.5, 0.7)),
                    _ => None,
                };
                if let Some((from, to, tc, q)) = sweep {
                    self.freq = (f * from).min(14_000.0);
                    self.freq_end = (f * to).min(9_000.0);
                    self.freq_k = glide_coef(sr, tc);
                    self.env2 = q;
                    self.filter = Biquad::new(FilterKind::LowPass, sr, self.freq, q);
                }
                self.len = u32::MAX;
            }
            Sound::Pad(Pad::Kick) => {
                self.gain = 1.0 * v;
                self.gain_k = decay_coef(sr, 0.45);
                self.freq = 165.0;
                self.freq_end = 46.0;
                self.freq_k = glide_coef(sr, 0.035);
                self.env2 = 0.35 * v; // short click at the start
                self.env2_k = decay_coef(sr, 0.006);
                self.len = secs(0.6);
            }
            Sound::Pad(Pad::Snare) => {
                self.gain = 0.55 * v;
                self.gain_k = decay_coef(sr, 0.2);
                self.env2 = 0.45 * v;
                self.env2_k = decay_coef(sr, 0.11);
                self.freq = 210.0;
                self.freq_end = 140.0;
                self.freq_k = glide_coef(sr, 0.03);
                self.filter = Biquad::new(FilterKind::HighPass, sr, 1400.0, 0.7);
                self.len = secs(0.35);
            }
            Sound::Pad(Pad::Clap) => {
                self.gain = 0.85 * v;
                self.gain_k = decay_coef(sr, 0.25);
                self.filter = Biquad::new(FilterKind::BandPass, sr, 1200.0, 0.9);
                self.len = secs(0.45);
            }
            Sound::Pad(Pad::Hat) => {
                self.gain = 0.4 * v;
                self.gain_k = decay_coef(sr, 0.06);
                self.filter = Biquad::new(FilterKind::HighPass, sr, 7500.0, 0.7);
                self.filter2 = Biquad::new(FilterKind::HighPass, sr, 7500.0, 0.7);
                self.len = secs(0.15);
            }
            Sound::Pad(Pad::Tom) => {
                self.gain = 0.8 * v;
                self.gain_k = decay_coef(sr, 0.35);
                self.freq = 200.0;
                self.freq_end = 98.0;
                self.freq_k = glide_coef(sr, 0.08);
                self.len = secs(0.5);
            }
            Sound::Pad(Pad::Cowbell) => {
                self.gain = 0.42 * v;
                self.gain_k = decay_coef(sr, 0.3);
                self.inc = [540.0 / sr, 800.0 / sr, 0.0, 0.0];
                self.filter = Biquad::new(FilterKind::BandPass, sr, 800.0, 1.5);
                self.len = secs(0.45);
            }
            Sound::Pad(Pad::Bass) => {
                // A1, a saw and a slightly detuned square under a closing low-pass.
                self.gain = 0.8 * v;
                self.gain_k = decay_coef(sr, 0.7);
                self.inc = [55.0 / sr, 55.3 / sr, 0.0, 0.0];
                self.env2 = 1.0;
                self.env2_k = glide_coef(sr, 0.09);
                self.filter = Biquad::new(FilterKind::LowPass, sr, 2400.0, 3.0);
                self.len = secs(0.9);
            }
            Sound::Pad(Pad::Chord) => {
                // A minor: A3, C4, E4.
                self.gain = 0.0;
                self.env2 = 0.6 * v; // target level, reached after a 6 ms attack
                self.gain_k = decay_coef(sr, 1.0);
                self.inc = [220.0 / sr, 261.63 / sr, 329.63 / sr, 0.0];
                self.filter = Biquad::new(FilterKind::LowPass, sr, 2600.0, 0.7);
                self.len = secs(1.3);
            }
            Sound::OpenHat => {
                self.gain = 0.34 * v;
                self.gain_k = decay_coef(sr, 0.34);
                self.filter = Biquad::new(FilterKind::HighPass, sr, 7000.0, 0.7);
                self.filter2 = Biquad::new(FilterKind::HighPass, sr, 7000.0, 0.7);
                self.len = secs(0.45);
            }
            Sound::Count(go) => {
                // A short square beep, a fifth or an octave above the tick.
                self.gain = 0.32 * v.max(0.5);
                self.gain_k = decay_coef(sr, 0.12);
                self.inc = [if go { 2093.0 } else { 1568.0 } / sr, 0.0, 0.0, 0.0];
                self.filter = Biquad::new(FilterKind::LowPass, sr, 4000.0, 0.7);
                self.len = secs(0.16);
            }
            Sound::Tick(accent) => {
                self.gain = if accent { 0.4 } else { 0.28 } * v.max(0.5);
                self.gain_k = decay_coef(sr, 0.05);
                self.inc = [if accent { 1568.0 } else { 1046.5 } / sr, 0.0, 0.0, 0.0];
                self.len = secs(0.08);
            }
        }
    }

    /// The next sample, or 0.0 once the voice has finished.
    #[inline]
    pub fn next(&mut self) -> f32 {
        if !self.active {
            return 0.0;
        }
        let sr = self.sample_rate;
        if let Sound::Note(timbre, _) = self.sound {
            return self.next_note(timbre);
        }
        let out = match self.sound {
            Sound::Note(..) => 0.0,
            Sound::Pad(Pad::Kick) | Sound::Pad(Pad::Tom) => {
                self.freq = self.freq_end + (self.freq - self.freq_end) * self.freq_k;
                self.phase[0] = (self.phase[0] + self.freq / sr).fract();
                let body = sin_turns(self.phase[0]) * self.gain;
                let click = self.noise.next() * self.env2;
                self.env2 *= self.env2_k;
                body + click
            }
            Sound::Pad(Pad::Snare) => {
                self.freq = self.freq_end + (self.freq - self.freq_end) * self.freq_k;
                self.phase[0] = (self.phase[0] + self.freq / sr).fract();
                let tone = sin_turns(self.phase[0]) * self.env2;
                self.env2 *= self.env2_k;
                let noise = self.filter.process(self.noise.next()) * self.gain;
                tone + noise
            }
            Sound::Pad(Pad::Clap) => {
                // Three quick bursts 11 ms apart, then a tail.
                let burst = (0.011 * sr) as u32;
                let env = if self.t < burst * 3 {
                    let since = (self.t % burst) as f32 / sr;
                    (-since / 0.003).exp() * 0.9
                } else {
                    self.gain
                };
                if self.t >= burst * 3 {
                    self.gain *= self.gain_k;
                }
                self.filter.process(self.noise.next()) * env * 1.6
            }
            Sound::Pad(Pad::Hat) | Sound::OpenHat => {
                let n = self.filter2.process(self.filter.process(self.noise.next()));
                n * self.gain
            }
            Sound::Pad(Pad::Cowbell) => {
                self.phase[0] = (self.phase[0] + self.inc[0]).fract();
                self.phase[1] = (self.phase[1] + self.inc[1]).fract();
                let s = square_turns(self.phase[0]) + square_turns(self.phase[1]);
                self.filter.process(s * 0.5) * self.gain * 2.0
            }
            Sound::Pad(Pad::Bass) => {
                self.phase[0] = (self.phase[0] + self.inc[0]).fract();
                self.phase[1] = (self.phase[1] + self.inc[1]).fract();
                // Sweep the filter from bright to dark; recompute every 32 samples.
                if self.t % 32 == 0 {
                    let cutoff = 260.0 + 2200.0 * self.env2;
                    self.filter.set(FilterKind::LowPass, sr, cutoff, 3.0);
                }
                self.env2 *= self.env2_k;
                let raw = saw_turns(self.phase[0]) * 0.7 + square_turns(self.phase[1]) * 0.3;
                self.filter.process(raw) * self.gain
            }
            Sound::Pad(Pad::Chord) => {
                let attack = (0.006 * sr) as u32;
                if self.t < attack {
                    self.gain = self.env2 * (self.t as f32 / attack as f32);
                } else if self.t == attack {
                    self.gain = self.env2;
                }
                let mut s = 0.0;
                for i in 0..3 {
                    self.phase[i] = (self.phase[i] + self.inc[i]).fract();
                    s += triangle_turns(self.phase[i]);
                }
                self.filter.process(s / 3.0) * self.gain
            }
            Sound::Tick(_) => {
                self.phase[0] = (self.phase[0] + self.inc[0]).fract();
                sin_turns(self.phase[0]) * self.gain
            }
            Sound::Count(_) => {
                self.phase[0] = (self.phase[0] + self.inc[0]).fract();
                self.filter.process(square_turns(self.phase[0])) * self.gain
            }
        };
        let in_attack = matches!(self.sound, Sound::Pad(Pad::Chord)) && self.t <= (0.006 * sr) as u32;
        // The clap steps its own gain (it holds still during the bursts).
        if !in_attack && !matches!(self.sound, Sound::Pad(Pad::Clap)) {
            self.gain *= self.gain_k;
        }
        self.t += 1;
        // env2 is a second audible layer only for these; elsewhere it is a filter or target level.
        let env2_audible = matches!(self.sound, Sound::Pad(Pad::Kick | Pad::Tom | Pad::Snare));
        let quiet = self.gain.abs() < 1e-5 && (!env2_audible || self.env2.abs() < 1e-5);
        if self.t >= self.len || (self.t > 64 && quiet && !in_attack) {
            self.active = false;
        }
        out
    }
}

impl Voice {
    /// The MIDI note this voice holds, if it is a keyboard note not yet released.
    pub fn held_note(&self) -> Option<u8> {
        match self.sound {
            Sound::Note(..) if self.active && self.stage != Stage::Release => Some(self.midi),
            _ => None,
        }
    }

    pub fn is_released(&self) -> bool {
        self.stage == Stage::Release
    }

    /// Lets go of a keyboard note: it fades with its timbre's release.
    pub fn release(&mut self) {
        if matches!(self.sound, Sound::Note(..)) && self.active {
            if self.stage == Stage::Attack {
                self.gain = self.peak * (self.t as f32 / self.attack as f32);
            }
            self.stage = Stage::Release;
        }
    }

    #[inline]
    fn next_note(&mut self, timbre: Timbre) -> f32 {
        match self.stage {
            Stage::Attack => {
                self.gain = self.peak * (self.t as f32 / self.attack as f32);
                if self.t >= self.attack {
                    self.gain = self.peak;
                    self.stage = Stage::Decay;
                }
            }
            Stage::Decay => self.gain = self.sustain + (self.gain - self.sustain) * self.decay_k,
            Stage::Release => self.gain *= self.release_k,
        }
        let mut s = 0.0;
        for i in 0..4 {
            if self.partial[i] == 0.0 && self.partial_floor[i] == 0.0 {
                continue;
            }
            self.phase[i] = (self.phase[i] + self.inc[i]).fract();
            let p = self.phase[i];
            let wave = match (timbre, i) {
                (Timbre::Piano, 0) | (Timbre::Guitar, 0) => triangle_turns(p),
                (Timbre::Synth, 0) | (Timbre::Synth, 1) => saw_turns(p),
                (Timbre::Synth, _) => square_turns(p),
                (Timbre::Strings, _) => saw_turns(p),
                (Timbre::Flute, 3) => self.noise.next(),
                // The modulator is heard only through the carrier; its level (the index) still decays.
                (Timbre::ElectricPiano, 1) => 0.0,
                (Timbre::ElectricPiano, 0) => sin_turns(p + 0.16 * self.partial[1] * sin_turns(self.phase[1])),
                _ => sin_turns(p),
            };
            s += wave * self.partial[i];
            self.partial[i] = self.partial_floor[i] + (self.partial[i] - self.partial_floor[i]) * self.partial_k[i];
        }
        if matches!(timbre, Timbre::Synth | Timbre::Guitar | Timbre::Strings) {
            if self.t % 32 == 0 {
                self.freq = self.freq_end + (self.freq - self.freq_end) * self.freq_k.powi(32);
                self.filter.set(FilterKind::LowPass, self.sample_rate, self.freq, self.env2);
            }
            s = self.filter.process(s);
        }
        self.t += 1;
        let quiet = self.gain < 1e-5 && self.stage != Stage::Attack;
        let dead = self.stage == Stage::Release || self.sustain == 0.0;
        if quiet && dead {
            self.active = false;
        }
        s * self.gain * self.track
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn render(sound: Sound) -> Vec<f32> {
        let mut v = Voice::silent();
        v.trigger(sound, 1.0, 48_000.0, 12345);
        let mut out = Vec::new();
        while v.is_active() && out.len() < 48_000 * 3 {
            out.push(v.next());
        }
        out
    }

    #[test]
    fn notes_sustain_while_held_and_stop_when_released() {
        for timbre in Timbre::ALL {
            let mut v = Voice::silent();
            v.trigger(Sound::Note(timbre, 60), 1.0, 48_000.0, 1);
            let held: Vec<f32> = (0..48_000).map(|_| v.next()).collect();
            assert!(held.iter().all(|x| x.is_finite()));
            let peak = held.iter().fold(0.0f32, |m, x| m.max(x.abs()));
            assert!(peak > 0.1 && peak < 1.2, "{timbre:?} peak {peak}");
            let sustains = matches!(timbre, Timbre::Organ | Timbre::Synth);
            let late = held[40_000..].iter().fold(0.0f32, |m, x| m.max(x.abs()));
            if sustains {
                assert!(v.is_active() && late > 0.05, "{timbre:?} holds while the key is down");
                assert_eq!(v.held_note(), Some(60));
            }
            v.release();
            assert_eq!(v.held_note(), None);
            let mut n = 0;
            while v.is_active() && n < 48_000 * 6 {
                v.next();
                n += 1;
            }
            assert!(!v.is_active(), "{timbre:?} stops after release");
        }
    }

    #[test]
    fn higher_notes_are_higher() {
        let crossings = |midi: u8| {
            let mut v = Voice::silent();
            v.trigger(Sound::Note(Timbre::Organ, midi), 1.0, 48_000.0, 1);
            let out: Vec<f32> = (0..24_000).map(|_| v.next()).collect();
            out.windows(2).filter(|w| (w[0] < 0.0) != (w[1] < 0.0)).count()
        };
        assert!(crossings(72) > crossings(60) && crossings(60) > crossings(48));
        assert!((midi_freq(69) - 440.0).abs() < 1e-3 && (midi_freq(57) - 220.0).abs() < 1e-3);
    }

    #[test]
    fn every_sound_is_finite_audible_and_ends() {
        let mut all: Vec<Sound> = Pad::ALL.iter().map(|&p| Sound::Pad(p)).collect();
        all.push(Sound::Tick(true));
        all.push(Sound::Tick(false));
        all.push(Sound::Count(true));
        all.push(Sound::Count(false));
        all.push(Sound::OpenHat);
        for s in all {
            let out = render(s);
            assert!(out.len() < 48_000 * 3, "{s:?} never stops");
            assert!(out.iter().all(|x| x.is_finite()), "{s:?} has NaN or infinity");
            let peak = out.iter().fold(0.0f32, |m, x| m.max(x.abs()));
            assert!(peak > 0.05, "{s:?} is too quiet: {peak}");
            assert!(peak < 1.2, "{s:?} is too loud: {peak}");
            let tail = &out[out.len().saturating_sub(64)..];
            let tail_peak = tail.iter().fold(0.0f32, |m, x| m.max(x.abs()));
            assert!(tail_peak < 0.05, "{s:?} ends with a click: {tail_peak}");
        }
    }
}
