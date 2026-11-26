//! Level 0.5, "Ripeti": the app plays a short phrase on the pads, the player
//! plays it back in the next bar.
//!
//! A phrase lives in one 4/4 bar on an eighth-note grid: step 0..8, two steps
//! per beat. Phrases grow with the round: first two notes on the beat with
//! kick and snare, later off-beats and more pads.

use crate::dsp::Noise;
use crate::voices::Pad;

pub const STEPS: u8 = 8;
pub const MAX_NOTES: usize = 8;
/// Within this distance from where it belongs a note counts as "a tempo".
pub const ON_TIME_MS: f64 = 70.0;
/// Beyond this distance a hit does not count as that note.
pub const NEAR_MS: f64 = 160.0;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Note {
    pub pad: u8,
    /// Eighth of the bar, 0..8.
    pub step: u8,
}

impl Note {
    /// Position within the bar, in beats.
    pub fn beat(&self) -> f64 {
        self.step as f64 / 2.0
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Phrase {
    notes: [Note; MAX_NOTES],
    len: u8,
}

impl Phrase {
    pub fn notes(&self) -> &[Note] {
        &self.notes[..self.len as usize]
    }

    pub fn from_notes(notes: &[Note]) -> Phrase {
        let mut p = Phrase { notes: [Note { pad: 0, step: 0 }; MAX_NOTES], len: 0 };
        for &n in notes.iter().take(MAX_NOTES) {
            p.notes[p.len as usize] = n;
            p.len += 1;
        }
        p
    }

    /// One i64 for JNI: note i in bits 6i..6i+6 (pad 3 bits, step 3 bits), length in bits 48..52.
    pub fn pack(&self) -> i64 {
        let mut v = (self.len as i64) << 48;
        for (i, n) in self.notes().iter().enumerate() {
            v |= (((n.pad as i64) & 7) | (((n.step as i64) & 7) << 3)) << (6 * i);
        }
        v
    }

    pub fn unpack(v: i64) -> Phrase {
        let len = ((v >> 48) & 0xF).min(MAX_NOTES as i64) as usize;
        let mut notes = [Note { pad: 0, step: 0 }; MAX_NOTES];
        for (i, n) in notes.iter_mut().enumerate().take(len) {
            let bits = (v >> (6 * i)) & 0x3F;
            *n = Note { pad: (bits & 7) as u8, step: ((bits >> 3) & 7) as u8 };
        }
        Phrase { notes, len: len as u8 }
    }
}

/// How hard round `round` (1-based) is: number of notes, pads in play, off-beats allowed.
pub fn difficulty(round: u32) -> (usize, &'static [Pad], bool) {
    const TWO: &[Pad] = &[Pad::Kick, Pad::Snare];
    const THREE: &[Pad] = &[Pad::Kick, Pad::Snare, Pad::Hat];
    const FIVE: &[Pad] = &[Pad::Kick, Pad::Snare, Pad::Hat, Pad::Clap, Pad::Tom];
    match round {
        0 | 1 => (2, TWO, false),
        2 => (3, TWO, false),
        3 => (3, THREE, false),
        4 => (4, THREE, false),
        5 => (4, THREE, true),
        6 => (5, FIVE, true),
        _ => (6, FIVE, true),
    }
}

/// A new phrase for `round`. Always starts on the first beat; one note per step.
pub fn make_phrase(round: u32, seed: u32) -> Phrase {
    let (count, pads, offbeats) = difficulty(round);
    let mut rng = Noise::new(seed ^ round.wrapping_mul(0x9E37_79B9));
    let mut pick = |n: usize| -> usize {
        let r = (rng.next() + 1.0) * 0.5; // 0..1
        ((r * n as f32) as usize).min(n - 1)
    };
    let candidates: &[u8] = if offbeats { &[1, 2, 3, 4, 5, 6, 7] } else { &[2, 4, 6] };
    let mut steps = [false; STEPS as usize];
    steps[0] = true;
    let mut chosen = 1;
    let mut guard = 0;
    while chosen < count.min(1 + candidates.len()) && guard < 200 {
        let s = candidates[pick(candidates.len())] as usize;
        if !steps[s] {
            steps[s] = true;
            chosen += 1;
        }
        guard += 1;
    }
    let mut notes = [Note { pad: 0, step: 0 }; MAX_NOTES];
    let mut len = 0;
    for (s, used) in steps.iter().enumerate() {
        if *used {
            // The first note is the kick: an anchor the ear finds at once.
            let pad = if s == 0 { Pad::Kick } else { pads[pick(pads.len())] };
            notes[len] = Note { pad: pad as u8, step: s as u8 };
            len += 1;
        }
    }
    Phrase { notes, len: len as u8 }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum NoteResult {
    OnTime = 0,
    Early = 1,
    Late = 2,
    Missed = 3,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Evaluation {
    results: [NoteResult; MAX_NOTES],
    len: u8,
    /// Hits that matched no note: wrong pad, or no note there.
    pub extra: u8,
}

impl Evaluation {
    pub fn results(&self) -> &[NoteResult] {
        &self.results[..self.len as usize]
    }

    /// Every note played, near enough, and nothing else.
    pub fn passed(&self) -> bool {
        self.extra == 0 && self.results().iter().all(|r| *r != NoteResult::Missed)
    }

    pub fn perfect(&self) -> bool {
        self.extra == 0 && self.results().iter().all(|r| *r == NoteResult::OnTime)
    }

    /// One i64 for JNI: result i in bits 2i..2i+2, length in 16..20, extra in 20..28,
    /// passed at bit 28, perfect at bit 29.
    pub fn pack(&self) -> i64 {
        let mut v = 0i64;
        for (i, r) in self.results().iter().enumerate() {
            v |= (*r as i64) << (2 * i);
        }
        v | ((self.len as i64) << 16)
            | ((self.extra.min(255) as i64) << 20)
            | ((self.passed() as i64) << 28)
            | ((self.perfect() as i64) << 29)
    }
}

/// Matches the player's hits to the phrase.
///
/// `hits` are (pad, position in beats from the start of the player's bar), in
/// any order. Each note takes the closest unused hit on its pad within
/// `NEAR_MS`; whatever is left over counts as extra.
pub fn evaluate(phrase: &Phrase, hits: &[(u8, f64)], ms_per_beat: f64) -> Evaluation {
    let mut used = [false; 64];
    let hits = &hits[..hits.len().min(used.len())];
    let mut results = [NoteResult::Missed; MAX_NOTES];
    for (i, note) in phrase.notes().iter().enumerate() {
        let mut best: Option<(usize, f64)> = None;
        for (j, &(pad, pos)) in hits.iter().enumerate() {
            if used[j] || pad != note.pad {
                continue;
            }
            let ms = (pos - note.beat()) * ms_per_beat;
            if ms.abs() <= NEAR_MS && best.map_or(true, |(_, b)| ms.abs() < b.abs()) {
                best = Some((j, ms));
            }
        }
        if let Some((j, ms)) = best {
            used[j] = true;
            results[i] = if ms.abs() <= ON_TIME_MS {
                NoteResult::OnTime
            } else if ms < 0.0 {
                NoteResult::Early
            } else {
                NoteResult::Late
            };
        }
    }
    let extra = used[..hits.len()].iter().filter(|u| !**u).count().min(255) as u8;
    Evaluation { results, len: phrase.len, extra }
}

#[cfg(test)]
mod tests {
    use super::*;

    const MPB: f64 = 750.0; // 80 BPM

    fn phrase() -> Phrase {
        Phrase::from_notes(&[Note { pad: 0, step: 0 }, Note { pad: 1, step: 2 }, Note { pad: 3, step: 5 }])
    }

    #[test]
    fn phrases_follow_the_difficulty() {
        for round in 1..12 {
            for seed in 1..50 {
                let p = make_phrase(round, seed);
                let (count, pads, offbeats) = difficulty(round);
                assert_eq!(p.notes().len(), count, "round {round}");
                assert_eq!(p.notes()[0], Note { pad: Pad::Kick as u8, step: 0 });
                for w in p.notes().windows(2) {
                    assert!(w[0].step < w[1].step, "sorted, one note per step");
                }
                for n in p.notes() {
                    assert!(n.step < STEPS);
                    assert!(pads.iter().any(|&q| q as u8 == n.pad) || n.step == 0);
                    if !offbeats {
                        assert_eq!(n.step % 2, 0, "round {round} stays on the beat");
                    }
                }
            }
        }
    }

    #[test]
    fn phrases_vary_with_the_seed() {
        let distinct: std::collections::HashSet<i64> = (1..40).map(|s| make_phrase(5, s).pack()).collect();
        assert!(distinct.len() > 10);
    }

    #[test]
    fn phrase_packing_round_trips() {
        for round in 1..10 {
            let p = make_phrase(round, round * 31 + 7);
            assert_eq!(Phrase::unpack(p.pack()), p);
        }
    }

    #[test]
    fn a_clean_take_is_perfect() {
        let e = evaluate(&phrase(), &[(0, 0.01), (1, 1.0), (3, 2.48)], MPB);
        assert!(e.passed() && e.perfect());
        assert_eq!(e.results(), &[NoteResult::OnTime; 3]);
    }

    #[test]
    fn a_bit_early_or_late_still_passes() {
        let e = evaluate(&phrase(), &[(0, -0.13), (1, 1.15), (3, 2.5)], MPB);
        assert_eq!(e.results(), &[NoteResult::Early, NoteResult::Late, NoteResult::OnTime]);
        assert!(e.passed());
        assert!(!e.perfect());
    }

    #[test]
    fn a_missing_note_fails() {
        let e = evaluate(&phrase(), &[(0, 0.0), (3, 2.5)], MPB);
        assert_eq!(e.results()[1], NoteResult::Missed);
        assert!(!e.passed());
    }

    #[test]
    fn the_wrong_pad_counts_as_missed_and_extra() {
        let e = evaluate(&phrase(), &[(0, 0.0), (2, 1.0), (3, 2.5)], MPB);
        assert_eq!(e.results()[1], NoteResult::Missed);
        assert_eq!(e.extra, 1);
        assert!(!e.passed());
    }

    #[test]
    fn extra_hits_fail_even_when_all_notes_are_there() {
        let e = evaluate(&phrase(), &[(0, 0.0), (1, 1.0), (3, 2.5), (0, 3.0)], MPB);
        assert_eq!(e.extra, 1);
        assert!(!e.passed());
    }

    #[test]
    fn too_far_does_not_count() {
        let e = evaluate(&phrase(), &[(0, 0.0), (1, 1.4), (3, 2.5)], MPB);
        assert_eq!(e.results()[1], NoteResult::Missed);
        assert_eq!(e.extra, 1);
    }

    #[test]
    fn evaluation_packs_its_flags() {
        let e = evaluate(&phrase(), &[(0, 0.0), (1, 1.15), (3, 2.5)], MPB);
        let v = e.pack();
        assert_eq!((v >> 16) & 0xF, 3);
        assert_eq!((v >> 28) & 1, 1);
        assert_eq!((v >> 29) & 1, 0);
        assert_eq!((v >> 2) & 3, NoteResult::Late as i64);
    }
}
