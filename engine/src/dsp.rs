//! Small DSP building blocks. Everything here is allocation-free and `Copy`.

use std::f32::consts::PI;

/// Per-sample multiplier that brings a level down by 60 dB in `seconds`.
pub fn decay_coef(sample_rate: f32, seconds: f32) -> f32 {
    if seconds <= 0.0 {
        return 0.0;
    }
    (0.001f32.ln() / (seconds * sample_rate)).exp()
}

/// Per-sample multiplier for an exponential glide with time constant `tau` seconds.
pub fn glide_coef(sample_rate: f32, tau: f32) -> f32 {
    (-1.0 / (tau * sample_rate)).exp()
}

/// Fast sine for a phase in turns (0..1). Deterministic: only + and *.
#[inline]
pub fn sin_turns(phase: f32) -> f32 {
    // Bring to -0.5..0.5, then a parabola refined once (error below 0.1%).
    let x = phase - (phase + 0.5).floor();
    let y = 8.0 * x - 16.0 * x * x.abs();
    0.225 * (y * y.abs() - y) + y
}

#[inline]
pub fn square_turns(phase: f32) -> f32 {
    if phase.fract() < 0.5 { 1.0 } else { -1.0 }
}

#[inline]
pub fn saw_turns(phase: f32) -> f32 {
    2.0 * phase.fract() - 1.0
}

#[inline]
pub fn triangle_turns(phase: f32) -> f32 {
    let p = phase.fract();
    if p < 0.5 { 4.0 * p - 1.0 } else { 3.0 - 4.0 * p }
}

/// Smooth saturation, so a pile of voices never clips hard.
#[inline]
pub fn soft_clip(x: f32) -> f32 {
    let x = x.clamp(-3.0, 3.0);
    (x * (27.0 + x * x) / (27.0 + 9.0 * x * x)).clamp(-1.0, 1.0)
}

/// White noise from a xorshift generator: cheap, deterministic, seedable.
#[derive(Clone, Copy, Debug)]
pub struct Noise(u32);

impl Noise {
    pub fn new(seed: u32) -> Self {
        Noise(seed.max(1))
    }
    #[inline]
    pub fn next(&mut self) -> f32 {
        let mut x = self.0;
        x ^= x << 13;
        x ^= x >> 17;
        x ^= x << 5;
        self.0 = x;
        (x as f32 / u32::MAX as f32) * 2.0 - 1.0
    }
}

/// RBJ biquad filter, direct form I.
#[derive(Clone, Copy, Debug, Default)]
pub struct Biquad {
    b0: f32,
    b1: f32,
    b2: f32,
    a1: f32,
    a2: f32,
    x1: f32,
    x2: f32,
    y1: f32,
    y2: f32,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FilterKind {
    LowPass,
    HighPass,
    BandPass,
}

impl Biquad {
    pub fn new(kind: FilterKind, sample_rate: f32, freq: f32, q: f32) -> Self {
        let mut f = Biquad::default();
        f.set(kind, sample_rate, freq, q);
        f
    }

    /// Changes the response, keeping the filter's memory (no clicks).
    pub fn set(&mut self, kind: FilterKind, sample_rate: f32, freq: f32, q: f32) {
        let freq = freq.clamp(10.0, sample_rate * 0.45);
        let w = 2.0 * PI * freq / sample_rate;
        let (sw, cw) = w.sin_cos();
        let alpha = sw / (2.0 * q.max(0.05));
        let (b0, b1, b2) = match kind {
            FilterKind::LowPass => ((1.0 - cw) / 2.0, 1.0 - cw, (1.0 - cw) / 2.0),
            FilterKind::HighPass => ((1.0 + cw) / 2.0, -(1.0 + cw), (1.0 + cw) / 2.0),
            FilterKind::BandPass => (alpha, 0.0, -alpha),
        };
        let a0 = 1.0 + alpha;
        self.b0 = b0 / a0;
        self.b1 = b1 / a0;
        self.b2 = b2 / a0;
        self.a1 = -2.0 * cw / a0;
        self.a2 = (1.0 - alpha) / a0;
    }

    #[inline]
    pub fn process(&mut self, x: f32) -> f32 {
        let y = self.b0 * x + self.b1 * self.x1 + self.b2 * self.x2 - self.a1 * self.y1 - self.a2 * self.y2;
        self.x2 = self.x1;
        self.x1 = x;
        self.y2 = self.y1;
        self.y1 = y;
        y
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fast_sine_is_close_to_the_real_one() {
        for i in 0..1000 {
            let p = i as f32 / 1000.0;
            let err = (sin_turns(p) - (2.0 * PI * p).sin()).abs();
            assert!(err < 0.002, "phase {p}: error {err}");
        }
    }

    #[test]
    fn decay_reaches_minus_60_db_on_time() {
        let sr = 48_000.0;
        let k = decay_coef(sr, 0.5);
        let level = k.powi((0.5 * sr) as i32);
        assert!((level - 0.001).abs() < 0.0001, "{level}");
    }

    #[test]
    fn soft_clip_is_bounded_and_monotonic() {
        let mut last = -2.0;
        for i in -400..=400 {
            let y = soft_clip(i as f32 / 50.0);
            assert!(y.abs() <= 1.0001);
            assert!(y >= last);
            last = y;
        }
    }

    #[test]
    fn lowpass_passes_low_and_cuts_high() {
        let sr = 48_000.0;
        let gain = |freq: f32| {
            let mut f = Biquad::new(FilterKind::LowPass, sr, 500.0, 0.707);
            let mut peak = 0.0f32;
            for n in 0..9600 {
                let y = f.process(sin_turns(freq * n as f32 / sr));
                if n > 4800 {
                    peak = peak.max(y.abs());
                }
            }
            peak
        };
        assert!(gain(100.0) > 0.9);
        assert!(gain(8000.0) < 0.02);
    }
}
