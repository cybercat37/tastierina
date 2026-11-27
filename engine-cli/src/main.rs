//! Renders every pad, and a bar of metronome, to WAV files.
//!
//!     cargo run -p engine-cli -- out/
//!
//! Handy to listen to a sound change on a computer, without the phone.

use std::fs::File;
use std::io::{BufWriter, Write};
use std::path::Path;

use engine::voices::{Pad, Timbre};
use engine::{engine, Command};

const SR: u32 = 48_000;

fn render(cmds: &[(usize, Command)], seconds: f32) -> Vec<f32> {
    let (mut control, mut renderer) = engine(SR as f64);
    let total = (seconds * SR as f32) as usize;
    let mut out = vec![0.0f32; total];
    let block = 256;
    let mut pos = 0;
    while pos < total {
        for (at, cmd) in cmds {
            if *at >= pos && *at < pos + block {
                control.send(*cmd);
            }
        }
        let end = (pos + block).min(total);
        renderer.render(&mut out[pos..end], 1);
        pos = end;
    }
    out
}

fn write_wav(path: &Path, samples: &[f32]) -> std::io::Result<()> {
    let mut w = BufWriter::new(File::create(path)?);
    let data_len = (samples.len() * 2) as u32;
    w.write_all(b"RIFF")?;
    w.write_all(&(36 + data_len).to_le_bytes())?;
    w.write_all(b"WAVEfmt ")?;
    w.write_all(&16u32.to_le_bytes())?;
    w.write_all(&1u16.to_le_bytes())?; // PCM
    w.write_all(&1u16.to_le_bytes())?; // mono
    w.write_all(&SR.to_le_bytes())?;
    w.write_all(&(SR * 2).to_le_bytes())?;
    w.write_all(&2u16.to_le_bytes())?;
    w.write_all(&16u16.to_le_bytes())?;
    w.write_all(b"data")?;
    w.write_all(&data_len.to_le_bytes())?;
    for &s in samples {
        w.write_all(&((s.clamp(-1.0, 1.0) * 32767.0) as i16).to_le_bytes())?;
    }
    w.flush()
}

fn main() -> std::io::Result<()> {
    let dir = std::env::args().nth(1).unwrap_or_else(|| "out".into());
    let dir = Path::new(&dir);
    std::fs::create_dir_all(dir)?;
    for pad in Pad::ALL {
        let samples = render(&[(0, Command::Hit { pad: pad as u8, velocity: 1.0 })], 1.5);
        let path = dir.join(format!("{}-{}.wav", pad as u8, pad.name()));
        write_wav(&path, &samples)?;
        println!("{}", path.display());
    }
    // Every sound of the drum machine (level 2), by id.
    for track in 0..engine::DRUM_TRACKS as u8 {
        let path = dir.join(format!("batteria-{track:02}.wav"));
        write_wav(&path, &render(&[(0, Command::Drum { track })], 2.0))?;
        println!("{}", path.display());
    }
    // A C major scale on each keyboard timbre, a quarter second per note.
    for t in Timbre::ALL {
        let mut cmds = Vec::new();
        for (k, midi) in [60u8, 62, 64, 65, 67, 69, 71, 72].into_iter().enumerate() {
            let at = k * (SR as usize / 4);
            cmds.push((at, Command::NoteOn { midi, timbre: t as u8, velocity: 0.9 }));
            cmds.push((at + SR as usize / 5, Command::NoteOff { midi }));
        }
        let path = dir.join(format!("tasti-{}.wav", t.name()));
        write_wav(&path, &render(&cmds, 3.0))?;
        println!("{}", path.display());
    }
    // Two bars of metronome at 100 BPM with the kick on every beat, slightly late.
    let mut cmds = vec![(0, Command::Metronome { on: true, bpm: 100.0 })];
    let first = (SR as f32 * 0.15) as usize;
    let beat = (SR as f32 * 0.6) as usize;
    for k in 0..8 {
        cmds.push((first + k * beat + 480, Command::Hit { pad: 0, velocity: 1.0 }));
    }
    let path = dir.join("metronomo.wav");
    write_wav(&path, &render(&cmds, 5.2))?;
    println!("{}", path.display());
    Ok(())
}
