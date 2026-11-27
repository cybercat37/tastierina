//! Level 1, "A tempo": a song played along the metronome, judged note by note.
//!
//! Each expected note has a key (0..18 of the keyboard) and a beat. A hit
//! takes the nearest pending note on its key within `NEAR_MS`; a hit that
//! takes no note is an error. A note nobody played by the time its window has
//! passed is missed. Everything is fixed-size: no allocation.

use crate::repeat::{NoteResult, NEAR_MS, ON_TIME_MS};

pub const MAX_NOTES: usize = 128;

/// State of one expected note.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum Mark {
    Pending = 0,
    OnTime = 1,
    Early = 2,
    Late = 3,
    Missed = 4,
}

impl From<NoteResult> for Mark {
    fn from(r: NoteResult) -> Mark {
        match r {
            NoteResult::OnTime => Mark::OnTime,
            NoteResult::Early => Mark::Early,
            NoteResult::Late => Mark::Late,
            NoteResult::Missed => Mark::Missed,
        }
    }
}

/// What a hit did.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum HitOutcome {
    /// It played note `index`, with this mark.
    Note { index: usize, mark: Mark },
    /// It matched no note: wrong key, or no note there.
    Wrong,
}

#[derive(Clone, Copy, Debug)]
pub struct Take {
    keys: [u8; MAX_NOTES],
    beats: [f64; MAX_NOTES],
    marks: [Mark; MAX_NOTES],
    offsets_ms: [f64; MAX_NOTES],
    len: usize,
    wrong: u32,
    /// Wrong hits per key, to say which key gives trouble.
    wrong_by_key: [u16; 32],
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Summary {
    pub notes: u32,
    pub on_time: u32,
    pub near: u32,
    pub missed: u32,
    pub wrong: u32,
    /// Mean signed distance from the beat of the notes played; negative is early.
    pub mean_offset_ms: f64,
    pub stars: u8,
    /// The key with most trouble (missed notes and wrong hits), if any.
    pub worst_key: Option<u8>,
}

impl Default for Take {
    fn default() -> Self {
        Take {
            keys: [0; MAX_NOTES],
            beats: [0.0; MAX_NOTES],
            marks: [Mark::Pending; MAX_NOTES],
            offsets_ms: [0.0; MAX_NOTES],
            len: 0,
            wrong: 0,
            wrong_by_key: [0; 32],
        }
    }
}

/// Stars for a take: 3 when nearly all on time, down to 0.
pub fn stars_for(notes: u32, on_time: u32, near: u32, wrong: u32) -> u8 {
    if notes == 0 {
        return 0;
    }
    let score = (on_time as f64 + 0.6 * near as f64 - 0.5 * wrong as f64) / notes as f64;
    match score {
        s if s >= 0.9 => 3,
        s if s >= 0.7 => 2,
        s if s >= 0.4 => 1,
        _ => 0,
    }
}

impl Take {
    pub fn reset(&mut self) {
        *self = Take::default();
    }

    /// Adds an expected note; returns false when the take is full.
    pub fn add_note(&mut self, key: u8, beat: f64) -> bool {
        if self.len >= MAX_NOTES {
            return false;
        }
        self.keys[self.len] = key;
        self.beats[self.len] = beat;
        self.len += 1;
        true
    }

    pub fn len(&self) -> usize {
        self.len
    }

    pub fn is_empty(&self) -> bool {
        self.len == 0
    }

    pub fn mark(&self, i: usize) -> Mark {
        if i < self.len { self.marks[i] } else { Mark::Pending }
    }

    /// A key played at `beat`.
    pub fn hit(&mut self, key: u8, beat: f64, ms_per_beat: f64) -> HitOutcome {
        let mut best: Option<(usize, f64)> = None;
        for i in 0..self.len {
            if self.marks[i] != Mark::Pending || self.keys[i] != key {
                continue;
            }
            let ms = (beat - self.beats[i]) * ms_per_beat;
            if ms.abs() <= NEAR_MS && best.map_or(true, |(_, b)| ms.abs() < b.abs()) {
                best = Some((i, ms));
            }
        }
        match best {
            Some((i, ms)) => {
                let mark = if ms.abs() <= ON_TIME_MS {
                    Mark::OnTime
                } else if ms < 0.0 {
                    Mark::Early
                } else {
                    Mark::Late
                };
                self.marks[i] = mark;
                self.offsets_ms[i] = ms;
                HitOutcome::Note { index: i, mark }
            }
            None => {
                self.wrong += 1;
                if let Some(c) = self.wrong_by_key.get_mut(key as usize) {
                    *c = c.saturating_add(1);
                }
                HitOutcome::Wrong
            }
        }
    }

    /// Marks as missed every pending note whose window closed before `beat`.
    /// Returns how many were marked now.
    pub fn expire(&mut self, beat: f64, ms_per_beat: f64) -> usize {
        let window = NEAR_MS / ms_per_beat;
        let mut n = 0;
        for i in 0..self.len {
            if self.marks[i] == Mark::Pending && beat > self.beats[i] + window {
                self.marks[i] = Mark::Missed;
                n += 1;
            }
        }
        n
    }

    /// Index of the first note still waiting to be played, if any.
    pub fn next_pending(&self) -> Option<usize> {
        (0..self.len).find(|&i| self.marks[i] == Mark::Pending)
    }

    pub fn finished(&self) -> bool {
        self.len > 0 && self.next_pending().is_none()
    }

    pub fn summary(&self) -> Summary {
        let mut on_time = 0;
        let mut near = 0;
        let mut missed = 0;
        let mut sum = 0.0;
        let mut played = 0;
        let mut trouble = [0u32; 32];
        for i in 0..self.len {
            match self.marks[i] {
                Mark::OnTime => on_time += 1,
                Mark::Early | Mark::Late => near += 1,
                Mark::Missed => {
                    missed += 1;
                    trouble[(self.keys[i] as usize).min(31)] += 1;
                }
                Mark::Pending => {}
            }
            if matches!(self.marks[i], Mark::OnTime | Mark::Early | Mark::Late) {
                sum += self.offsets_ms[i];
                played += 1;
            }
        }
        for (k, w) in self.wrong_by_key.iter().enumerate() {
            trouble[k] += *w as u32;
        }
        let (worst, most) = trouble.iter().enumerate().fold((0usize, 0u32), |acc, (k, &c)| if c > acc.1 { (k, c) } else { acc });
        Summary {
            notes: self.len as u32,
            on_time,
            near,
            missed,
            wrong: self.wrong,
            mean_offset_ms: if played > 0 { sum / played as f64 } else { 0.0 },
            stars: stars_for(self.len as u32, on_time, near, self.wrong),
            worst_key: if most >= 2 { Some(worst as u8) } else { None },
        }
    }
}

impl Summary {
    /// One i64 for JNI: notes 0..8, on time 8..16, near 16..24, missed 24..32,
    /// wrong 32..40, stars 40..42, worst key + 1 (0 = none) 42..48,
    /// mean offset in ms + 512 (clamped) 48..58.
    pub fn pack(&self) -> i64 {
        let b = |v: u32| (v.min(255)) as i64;
        let offset = (self.mean_offset_ms.round() + 512.0).clamp(0.0, 1023.0) as i64;
        b(self.notes)
            | b(self.on_time) << 8
            | b(self.near) << 16
            | b(self.missed) << 24
            | b(self.wrong) << 32
            | (self.stars as i64 & 3) << 40
            | (self.worst_key.map_or(0, |k| k as i64 + 1) & 63) << 42
            | offset << 48
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const MPB: f64 = 500.0; // 120 BPM

    fn take() -> Take {
        let mut t = Take::default();
        for (i, key) in [4u8, 4, 5, 7].into_iter().enumerate() {
            t.add_note(key, 4.0 + i as f64);
        }
        t
    }

    #[test]
    fn a_clean_take_gets_three_stars() {
        let mut t = take();
        for (i, key) in [4u8, 4, 5, 7].into_iter().enumerate() {
            assert_eq!(t.hit(key, 4.0 + i as f64 + 0.02, MPB), HitOutcome::Note { index: i, mark: Mark::OnTime });
        }
        assert!(t.finished());
        let s = t.summary();
        assert_eq!((s.on_time, s.near, s.missed, s.wrong, s.stars), (4, 0, 0, 0, 3));
        assert!((s.mean_offset_ms - 10.0).abs() < 1e-6);
        assert_eq!(s.worst_key, None);
    }

    #[test]
    fn repeated_notes_are_taken_in_order_of_closeness() {
        let mut t = take();
        // Two Mi in a row: each hit takes the one it is closest to.
        assert_eq!(t.hit(4, 5.05, MPB), HitOutcome::Note { index: 1, mark: Mark::OnTime });
        assert_eq!(t.hit(4, 3.9, MPB), HitOutcome::Note { index: 0, mark: Mark::OnTime });
    }

    #[test]
    fn early_late_wrong_and_missed() {
        let mut t = take();
        assert_eq!(t.hit(4, 3.8, MPB), HitOutcome::Note { index: 0, mark: Mark::Early });
        assert_eq!(t.hit(4, 5.2, MPB), HitOutcome::Note { index: 1, mark: Mark::Late });
        assert_eq!(t.hit(9, 6.0, MPB), HitOutcome::Wrong);
        assert_eq!(t.hit(9, 6.1, MPB), HitOutcome::Wrong);
        assert_eq!(t.expire(6.5, MPB), 1, "the Fa at beat 6 is gone");
        assert!(!t.finished());
        assert_eq!(t.next_pending(), Some(3));
        assert_eq!(t.expire(8.0, MPB), 1);
        let s = t.summary();
        assert_eq!((s.on_time, s.near, s.missed, s.wrong), (0, 2, 2, 2));
        assert_eq!(s.worst_key, Some(9), "two wrong La");
        assert_eq!(s.stars, 0);
    }

    #[test]
    fn missing_notes_are_marked_only_after_their_window() {
        let mut t = take();
        assert_eq!(t.expire(4.2, MPB), 0, "still within 160 ms of beat 4");
        assert_eq!(t.expire(4.4, MPB), 1);
        assert_eq!(t.mark(0), Mark::Missed);
    }

    #[test]
    fn stars_follow_the_score() {
        assert_eq!(stars_for(10, 10, 0, 0), 3);
        assert_eq!(stars_for(10, 7, 2, 0), 2);
        assert_eq!(stars_for(10, 5, 2, 1), 1);
        assert_eq!(stars_for(10, 2, 2, 4), 0);
        assert_eq!(stars_for(0, 0, 0, 0), 0);
    }

    #[test]
    fn a_full_take_refuses_more_notes() {
        let mut t = Take::default();
        for i in 0..MAX_NOTES {
            assert!(t.add_note(0, i as f64));
        }
        assert!(!t.add_note(0, 999.0));
        t.reset();
        assert!(t.is_empty());
    }

    #[test]
    fn summary_packs_its_fields() {
        let s = Summary { notes: 30, on_time: 20, near: 6, missed: 4, wrong: 3, mean_offset_ms: -42.4, stars: 2, worst_key: Some(6) };
        let v = s.pack();
        assert_eq!(v & 0xFF, 30);
        assert_eq!((v >> 8) & 0xFF, 20);
        assert_eq!((v >> 16) & 0xFF, 6);
        assert_eq!((v >> 24) & 0xFF, 4);
        assert_eq!((v >> 32) & 0xFF, 3);
        assert_eq!((v >> 40) & 3, 2);
        assert_eq!((v >> 42) & 63, 7);
        assert_eq!(((v >> 48) & 1023) - 512, -42);
    }
}
