//! Golden test on the sound: each pad is rendered offline and described by a
//! few features (peak, loudness, brightness, length). A change to how a pad
//! sounds shows up here. To accept a deliberate change:
//!
//!     UPDATE_GOLDEN=1 cargo test -p engine --test golden

use engine::voices::{Kit, Pad, Timbre};
use engine::{engine, Command};

const SR: f64 = 48_000.0;
const GOLDEN: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/tests/golden.txt");
/// Allowed relative drift per feature.
const TOLERANCE: f64 = 0.03;

struct Features {
    peak: f64,
    rms: f64,
    /// Zero crossings per second: a rough brightness.
    crossings: f64,
    /// Milliseconds until the sound falls below -60 dB for good.
    length_ms: f64,
}

fn features(start: Command, release: Option<Command>) -> Features {
    let (mut control, mut renderer) = engine(SR);
    control.send(Command::Volume(1.0));
    control.send(start);
    let mut out = vec![0.0f32; 48_000 * 3];
    for (i, block) in out.chunks_mut(256).enumerate() {
        // Keys are held for half a second.
        if i * 256 == 24_064 {
            if let Some(cmd) = release {
                control.send(cmd);
            }
        }
        renderer.render(block, 1);
    }
    let peak = out.iter().fold(0.0f32, |m, s| m.max(s.abs())) as f64;
    let last_loud = out.iter().rposition(|s| s.abs() > 0.001).unwrap_or(0);
    let body = &out[..=last_loud];
    let rms = (body.iter().map(|&s| (s as f64) * (s as f64)).sum::<f64>() / body.len() as f64).sqrt();
    let crossings = body.windows(2).filter(|w| (w[0] < 0.0) != (w[1] < 0.0)).count() as f64
        / (body.len() as f64 / SR);
    Features { peak, rms, crossings, length_ms: last_loud as f64 / SR * 1000.0 }
}

fn line(name: &str, f: &Features) -> String {
    format!("{name} peak={:.4} rms={:.4} crossings={:.0} length_ms={:.1}", f.peak, f.rms, f.crossings, f.length_ms)
}

#[test]
fn every_pad_still_sounds_the_same() {
    let mut lines: Vec<String> = Pad::ALL
        .iter()
        .map(|&p| line(p.name(), &features(Command::Hit { pad: p as u8, velocity: 1.0 }, None)))
        .collect();
    // The drum machine's own sounds (ids 4 and 7..16; the others are pads).
    lines.push(line("hat-aperto", &features(Command::Drum { track: 4 }, None)));
    for (i, k) in Kit::ALL.iter().enumerate() {
        lines.push(line(k.name(), &features(Command::Drum { track: 7 + i as u8 }, None)));
    }
    for t in Timbre::ALL {
        let on = Command::NoteOn { midi: 60, timbre: t as u8, velocity: 1.0 };
        lines.push(line(t.name(), &features(on, Some(Command::NoteOff { midi: 60 }))));
    }
    if std::env::var_os("UPDATE_GOLDEN").is_some() {
        std::fs::write(GOLDEN, lines.join("\n") + "\n").unwrap();
        return;
    }
    let golden = std::fs::read_to_string(GOLDEN).expect("tests/golden.txt missing: run with UPDATE_GOLDEN=1");
    let mut failures = Vec::new();
    for (got, want) in lines.iter().zip(golden.lines()) {
        let parse = |l: &str| -> Vec<(String, f64)> {
            l.split_whitespace()
                .skip(1)
                .map(|kv| {
                    let (k, v) = kv.split_once('=').unwrap();
                    (k.to_string(), v.parse().unwrap())
                })
                .collect()
        };
        for ((k, g), (_, w)) in parse(got).into_iter().zip(parse(want)) {
            let drift = if w.abs() < 1e-9 { g.abs() } else { ((g - w) / w).abs() };
            if drift > TOLERANCE {
                failures.push(format!("{}: {k} is {g}, golden {w}", got.split(' ').next().unwrap()));
            }
        }
    }
    assert_eq!(lines.len(), golden.lines().count(), "number of sounds changed");
    assert!(failures.is_empty(), "sound changed:\n{}", failures.join("\n"));
}
