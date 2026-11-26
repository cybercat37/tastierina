//! Time on the audio clock, and the "Tieni il tempo" judge.
//!
//! Positions are in output frames: frame `n` is the n-th frame the engine has
//! rendered since the stream started. The platform reports which frame was
//! leaving the speaker at a given monotonic time; with that pair, a touch's
//! monotonic timestamp becomes "the frame the player was hearing", and the
//! output latency drops out of the judgment on its own.

/// A (frame, time) pair from the audio device: `frame` was presented at `nanos`.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Presentation {
    pub frame: i64,
    pub nanos: i64,
}

/// The frame that was being heard at monotonic time `nanos`.
pub fn heard_frame(at: Presentation, nanos: i64, sample_rate: f64) -> f64 {
    at.frame as f64 + (nanos - at.nanos) as f64 * sample_rate / 1e9
}

/// The beat grid of the metronome: beat `k` starts at `origin + k * frames_per_beat`.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct BeatGrid {
    pub origin: f64,
    pub frames_per_beat: f64,
}

impl BeatGrid {
    pub fn new(origin: f64, bpm: f64, sample_rate: f64) -> Self {
        BeatGrid { origin, frames_per_beat: sample_rate * 60.0 / bpm }
    }

    /// Beats since beat 0 at `frame`, possibly negative or fractional.
    pub fn position(&self, frame: f64) -> f64 {
        (frame - self.origin) / self.frames_per_beat
    }

    pub fn frame_of(&self, beat: i64) -> f64 {
        self.origin + beat as f64 * self.frames_per_beat
    }

    /// The same musical position at `frame`, at a new tempo: changing BPM never jumps.
    pub fn retimed(&self, frame: f64, bpm: f64, sample_rate: f64) -> BeatGrid {
        let pos = self.position(frame);
        let fpb = sample_rate * 60.0 / bpm;
        BeatGrid { origin: frame - pos * fpb, frames_per_beat: fpb }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum Verdict {
    OnTime = 0,
    Early = 1,
    Late = 2,
    /// Too far from any beat to count.
    Off = 3,
}

/// Within this distance from the beat a hit is "a tempo".
pub const ON_TIME_MS: f64 = 40.0;
/// Beyond this distance a hit is not near any beat.
pub const NEAR_MS: f64 = 120.0;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Judgment {
    /// The nearest beat.
    pub beat: i64,
    /// Negative is early, positive is late.
    pub offset_ms: f64,
    pub verdict: Verdict,
}

/// Judges a hit heard at `frame` against the grid.
pub fn judge(grid: BeatGrid, frame: f64, sample_rate: f64) -> Judgment {
    let beat = grid.position(frame).round() as i64;
    let offset_ms = (frame - grid.frame_of(beat)) / sample_rate * 1000.0;
    let verdict = if offset_ms.abs() <= ON_TIME_MS {
        Verdict::OnTime
    } else if offset_ms.abs() > NEAR_MS {
        Verdict::Off
    } else if offset_ms < 0.0 {
        Verdict::Early
    } else {
        Verdict::Late
    };
    Judgment { beat, offset_ms, verdict }
}

impl Judgment {
    /// One `i64` for the JNI boundary: verdict in bits 32..40, offset in microseconds below.
    pub fn pack(&self) -> i64 {
        let micros = (self.offset_ms * 1000.0).round().clamp(i32::MIN as f64, i32::MAX as f64) as i32;
        ((self.verdict as i64) << 32) | (micros as u32 as i64)
    }

    pub fn unpack_verdict(packed: i64) -> u8 {
        ((packed >> 32) & 0xFF) as u8
    }

    pub fn unpack_offset_ms(packed: i64) -> f64 {
        (packed as u32 as i32) as f64 / 1000.0
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SR: f64 = 48_000.0;

    #[test]
    fn touch_time_maps_onto_the_frame_being_heard() {
        let at = Presentation { frame: 96_000, nanos: 5_000_000_000 };
        // 10 ms after the presented frame, 480 frames later.
        assert!((heard_frame(at, 5_010_000_000, SR) - 96_480.0).abs() < 1e-6);
        // and before it too.
        assert!((heard_frame(at, 4_990_000_000, SR) - 95_520.0).abs() < 1e-6);
    }

    #[test]
    fn verdicts_follow_the_thresholds() {
        let grid = BeatGrid::new(4800.0, 120.0, SR); // a beat every 24 000 frames
        let at_ms = |beat: i64, ms: f64| grid.frame_of(beat) + ms * SR / 1000.0;
        assert_eq!(judge(grid, at_ms(3, 0.0), SR).verdict, Verdict::OnTime);
        assert_eq!(judge(grid, at_ms(3, -39.0), SR).verdict, Verdict::OnTime);
        assert_eq!(judge(grid, at_ms(3, 39.0), SR).verdict, Verdict::OnTime);
        assert_eq!(judge(grid, at_ms(3, -80.0), SR).verdict, Verdict::Early);
        assert_eq!(judge(grid, at_ms(3, 80.0), SR).verdict, Verdict::Late);
        assert_eq!(judge(grid, at_ms(3, 200.0), SR).verdict, Verdict::Off);
        let j = judge(grid, at_ms(5, -25.0), SR);
        assert_eq!(j.beat, 5);
        assert!((j.offset_ms + 25.0).abs() < 1e-6);
    }

    #[test]
    fn halfway_between_beats_snaps_to_one_of_them() {
        let grid = BeatGrid::new(0.0, 60.0, SR);
        let j = judge(grid, 24_000.0 + 1.0, SR);
        assert_eq!(j.beat, 1);
        assert_eq!(j.verdict, Verdict::Off);
    }

    #[test]
    fn retiming_keeps_the_position() {
        let grid = BeatGrid::new(1000.0, 90.0, SR);
        let now = 123_456.0;
        let faster = grid.retimed(now, 140.0, SR);
        assert!((faster.position(now) - grid.position(now)).abs() < 1e-9);
        assert!((faster.frames_per_beat - SR * 60.0 / 140.0).abs() < 1e-9);
    }

    #[test]
    fn packing_round_trips() {
        for (v, ms) in [(Verdict::OnTime, 0.0), (Verdict::Early, -87.25), (Verdict::Late, 119.5), (Verdict::Off, -480.0)] {
            let j = Judgment { beat: 7, offset_ms: ms, verdict: v };
            let p = j.pack();
            assert_eq!(Judgment::unpack_verdict(p), v as u8);
            assert!((Judgment::unpack_offset_ms(p) - ms).abs() < 0.001);
        }
    }
}
