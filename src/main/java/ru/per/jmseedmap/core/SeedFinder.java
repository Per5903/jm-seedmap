package ru.per.jmseedmap.core;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import org.jspecify.annotations.Nullable;

/**
 * Recovers the seed from the hash the server sends ({@code BiomeManager.obfuscateSeed}, SHA-256 of the seed).
 * A 64-bit seed cannot be brute-forced, but two common cases are small enough:
 * <ul>
 *   <li>seeds typed as text or as a number that fits in 32 bits ({@code String.hashCode()}): 2^32 candidates,
 *       a few minutes on all cores, nearest-to-zero first;</li>
 *   <li>the lower 48 bits already known (the "structure seed" SeedCrackerX finds): 2^16 candidates, instant.</li>
 * </ul>
 */
public final class SeedFinder {
	public enum Mode { INT32, STRUCTURE_SEED }

	private static final long BLOCK = 1L << 20;
	private static final long INT32_BLOCKS = (1L << 31) / BLOCK;

	private final AtomicBoolean running = new AtomicBoolean();
	private final AtomicLong checked = new AtomicLong();
	private volatile long total;
	private volatile @Nullable Long found;
	private volatile boolean finished;
	private volatile @Nullable Mode mode;
	private volatile long startedAt;

	public boolean isRunning() {
		return running.get();
	}

	public @Nullable Mode mode() {
		return mode;
	}

	/** 0..100 */
	public int progress() {
		long t = total;
		return t == 0 ? 0 : (int) Math.min(100, checked.get() * 100 / t);
	}

	public long checked() {
		return checked.get();
	}

	public long elapsedMillis() {
		return System.currentTimeMillis() - startedAt;
	}

	public boolean finished() {
		return finished;
	}

	public @Nullable Long found() {
		return found;
	}

	public void cancel() {
		running.set(false);
	}

	/**
	 * @param onDone called from a worker thread with the seed, or never if cancelled; {@link #found()} is null
	 *               after a full search without result
	 */
	public boolean start(Mode mode, long hashedSeed, long structureSeed, LongConsumer onDone, Runnable onNotFound) {
		if (!running.compareAndSet(false, true)) {
			return false;
		}
		this.mode = mode;
		found = null;
		finished = false;
		checked.set(0);
		startedAt = System.currentTimeMillis();
		int threads = mode == Mode.STRUCTURE_SEED ? 1 : Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
		total = mode == Mode.STRUCTURE_SEED ? 1L << 16 : 1L << 32;
		AtomicLong nextBlock = new AtomicLong();
		AtomicLong liveThreads = new AtomicLong(threads);
		for (int i = 0; i < threads; i++) {
			Thread thread = new Thread(() -> {
				try {
					OptionalLong result = mode == Mode.STRUCTURE_SEED
						? searchUpperBits(hashedSeed, structureSeed)
						: searchInt32(hashedSeed, nextBlock);
					if (result.isPresent() && found == null) {
						found = result.getAsLong();
						running.set(false);
						onDone.accept(result.getAsLong());
					}
				} finally {
					if (liveThreads.decrementAndGet() == 0) {
						boolean completed = running.getAndSet(false) || found != null;
						finished = true;
						if (found == null && completed && checked.get() >= total) {
							onNotFound.run();
						}
					}
				}
			}, "SeedMap seed finder " + i);
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY);
			thread.start();
		}
		return true;
	}

	private OptionalLong searchInt32(long hashedSeed, AtomicLong nextBlock) {
		Hasher hasher = new Hasher();
		while (running.get()) {
			long block = nextBlock.getAndIncrement();
			if (block >= INT32_BLOCKS) {
				return OptionalLong.empty();
			}
			// Block k covers [k*B, (k+1)*B) and its negative mirror, so small seeds come first.
			long from = block * BLOCK;
			for (long s = from; s < from + BLOCK; s++) {
				if (hasher.hash(s) == hashedSeed) {
					return OptionalLong.of(s);
				}
				long negative = -s - 1;
				if (hasher.hash(negative) == hashedSeed) {
					return OptionalLong.of(negative);
				}
			}
			checked.addAndGet(BLOCK * 2);
		}
		return OptionalLong.empty();
	}

	private OptionalLong searchUpperBits(long hashedSeed, long structureSeed) {
		Hasher hasher = new Hasher();
		long lower = structureSeed & ((1L << 48) - 1);
		for (long upper = 0; upper < (1L << 16) && running.get(); upper++) {
			long seed = upper << 48 | lower;
			checked.incrementAndGet();
			if (hasher.hash(seed) == hashedSeed) {
				return OptionalLong.of(seed);
			}
		}
		return OptionalLong.empty();
	}

	/** Same as Guava's {@code Hashing.sha256().hashLong(seed).asLong()}, without allocations per call. */
	private static final class Hasher {
		private final MessageDigest sha;
		private final byte[] input = new byte[8];
		private final byte[] output = new byte[32];

		Hasher() {
			try {
				sha = MessageDigest.getInstance("SHA-256");
			} catch (NoSuchAlgorithmException e) {
				throw new IllegalStateException(e);
			}
		}

		long hash(long seed) {
			for (int i = 0; i < 8; i++) {
				input[i] = (byte) (seed >>> (8 * i));
			}
			sha.update(input);
			try {
				sha.digest(output, 0, 32);
			} catch (java.security.DigestException e) {
				throw new IllegalStateException(e);
			}
			long result = 0;
			for (int i = 7; i >= 0; i--) {
				result = result << 8 | (output[i] & 0xFF);
			}
			return result;
		}
	}
}
