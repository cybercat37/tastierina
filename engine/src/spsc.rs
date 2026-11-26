//! A fixed-size single-producer, single-consumer queue for `Copy` values.
//!
//! The UI thread pushes commands, the audio thread pops them. Neither side
//! allocates or blocks: a full queue makes `push` return `false`.

use std::cell::UnsafeCell;
use std::mem::MaybeUninit;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;

struct Ring<T, const N: usize> {
    buf: [UnsafeCell<MaybeUninit<T>>; N],
    /// Index of the next slot to read. Written only by the consumer.
    head: AtomicUsize,
    /// Index of the next slot to write. Written only by the producer.
    tail: AtomicUsize,
}

// SAFETY: a slot is written by the producer before `tail` is published with
// Release, and read by the consumer only after observing that `tail` with
// Acquire; `head` hands slots back the same way. Each slot has one owner at a time.
unsafe impl<T: Send, const N: usize> Sync for Ring<T, N> {}

/// The writing end. Keep exactly one.
pub struct Producer<T, const N: usize>(Arc<Ring<T, N>>);
/// The reading end. Keep exactly one.
pub struct Consumer<T, const N: usize>(Arc<Ring<T, N>>);

/// Makes a queue holding up to `N` values.
pub fn channel<T: Copy + Send, const N: usize>() -> (Producer<T, N>, Consumer<T, N>) {
    assert!(N > 0, "a queue needs at least one slot");
    let ring = Arc::new(Ring {
        buf: std::array::from_fn(|_| UnsafeCell::new(MaybeUninit::uninit())),
        head: AtomicUsize::new(0),
        tail: AtomicUsize::new(0),
    });
    (Producer(Arc::clone(&ring)), Consumer(ring))
}

impl<T: Copy + Send, const N: usize> Producer<T, N> {
    /// Adds a value; returns `false` and drops it when the queue is full.
    pub fn push(&mut self, value: T) -> bool {
        let ring = &*self.0;
        let tail = ring.tail.load(Ordering::Relaxed);
        let head = ring.head.load(Ordering::Acquire);
        if tail.wrapping_sub(head) >= N {
            return false;
        }
        // SAFETY: the slot at `tail` is not visible to the consumer until the store below.
        unsafe { (*ring.buf[tail % N].get()).write(value) };
        ring.tail.store(tail.wrapping_add(1), Ordering::Release);
        true
    }
}

impl<T: Copy + Send, const N: usize> Consumer<T, N> {
    /// Takes the oldest value, if any.
    pub fn pop(&mut self) -> Option<T> {
        let ring = &*self.0;
        let head = ring.head.load(Ordering::Relaxed);
        let tail = ring.tail.load(Ordering::Acquire);
        if head == tail {
            return None;
        }
        // SAFETY: the producer published this slot before moving `tail` past it.
        let value = unsafe { (*ring.buf[head % N].get()).assume_init_read() };
        ring.head.store(head.wrapping_add(1), Ordering::Release);
        Some(value)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keeps_order_and_reports_full() {
        let (mut tx, mut rx) = channel::<u32, 4>();
        for i in 0..4 {
            assert!(tx.push(i));
        }
        assert!(!tx.push(99), "a full queue refuses");
        for i in 0..4 {
            assert_eq!(rx.pop(), Some(i));
        }
        assert_eq!(rx.pop(), None);
    }

    #[test]
    fn wraps_around_many_times() {
        let (mut tx, mut rx) = channel::<u64, 3>();
        for i in 0..10_000u64 {
            assert!(tx.push(i));
            assert_eq!(rx.pop(), Some(i));
        }
    }

    #[test]
    fn works_across_threads() {
        let (mut tx, mut rx) = channel::<u64, 64>();
        let n = 200_000u64;
        let producer = std::thread::spawn(move || {
            let mut i = 0;
            while i < n {
                if tx.push(i) {
                    i += 1;
                }
            }
        });
        let mut expected = 0;
        while expected < n {
            if let Some(v) = rx.pop() {
                assert_eq!(v, expected);
                expected += 1;
            }
        }
        producer.join().unwrap();
    }
}
