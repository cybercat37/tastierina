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

/// What a voice is playing: a pad, or the metronome tick.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Sound {
    Pad(Pad),
    /// `true` for the first beat of the bar.
    Tick(bool),
    /// Count-in before the player's turn; `true` for the last count ("1": go).
    Count(bool),
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
    phase: [f32; 3],
    /// Phase increment per sample, in turns.
    inc: [f32; 3],
    /// Pitch glide: current and target frequency, glide multiplier.
    freq: f32,
    freq_end: f32,
    freq_k: f32,
    noise: Noise,
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
            phase: [0.0; 3],
            inc: [0.0; 3],
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
                self.inc = [540.0 / sr, 800.0 / sr, 0.0];
                self.filter = Biquad::new(FilterKind::BandPass, sr, 800.0, 1.5);
                self.len = secs(0.45);
            }
            Sound::Pad(Pad::Bass) => {
                // A1, a saw and a slightly detuned square under a closing low-pass.
                self.gain = 0.8 * v;
                self.gain_k = decay_coef(sr, 0.7);
                self.inc = [55.0 / sr, 55.3 / sr, 0.0];
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
                self.inc = [220.0 / sr, 261.63 / sr, 329.63 / sr];
                self.filter = Biquad::new(FilterKind::LowPass, sr, 2600.0, 0.7);
                self.len = secs(1.3);
            }
            Sound::Count(go) => {
                // A short square beep, a fifth or an octave above the tick.
                self.gain = 0.32 * v.max(0.5);
                self.gain_k = decay_coef(sr, 0.12);
                self.inc = [if go { 2093.0 } else { 1568.0 } / sr, 0.0, 0.0];
                self.filter = Biquad::new(FilterKind::LowPass, sr, 4000.0, 0.7);
                self.len = secs(0.16);
            }
            Sound::Tick(accent) => {
                self.gain = if accent { 0.4 } else { 0.28 } * v.max(0.5);
                self.gain_k = decay_coef(sr, 0.05);
                self.inc = [if accent { 1568.0 } else { 1046.5 } / sr, 0.0, 0.0];
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
        let out = match self.sound {
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
            Sound::Pad(Pad::Hat) => {
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
    fn every_sound_is_finite_audible_and_ends() {
        let mut all: Vec<Sound> = Pad::ALL.iter().map(|&p| Sound::Pad(p)).collect();
        all.push(Sound::Tick(true));
        all.push(Sound::Tick(false));
        all.push(Sound::Count(true));
        all.push(Sound::Count(false));
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
