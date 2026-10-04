package ru.per.jmseedmap.compat;

import java.lang.reflect.Field;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import ru.per.jmseedmap.SeedMapClient;
import ru.per.jmseedmap.SeedMapConfig;
import ru.per.jmseedmap.core.SeedMap;
import ru.per.jmseedmap.gen.GenContextProvider;

/**
 * SeedCrackerX integration without a compile-time dependency.
 * <ul>
 *   <li>World seed: SeedCrackerX calls every {@code "seedcrackerx"} entrypoint with the seed it found. Ours is declared
 *       as a method reference ({@code SeedCrackerCompat::pushWorldSeed}); Fabric wraps it in a proxy of their
 *       {@code SeedCrackerAPI} interface, so no class of theirs is needed to build.</li>
 *   <li>Structure seed: when it has narrowed the seed down to one 48-bit structure seed but not the full seed,
 *       the upper 16 bits are recovered from the server's seed hash (2^16 tries, instant).</li>
 * </ul>
 */
public final class SeedCrackerCompat {
	private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("seedcrackerx");
	private static @Nullable Long lastStructureSeed;
	private static boolean reflectionFailed;

	private SeedCrackerCompat() {
	}

	/** Entrypoint target, called by SeedCrackerX (from its own thread) with a found world seed. */
	public static void pushWorldSeed(long seed) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> SeedMap.get().onExternalSeed(seed, "SeedCrackerX"));
	}

	public static boolean isLoaded() {
		return LOADED;
	}

	/** Every few seconds: is there a single structure seed and no world seed yet? */
	public static void tick(SeedMap seedMap, Minecraft mc) {
		if (!LOADED || reflectionFailed || mc.getSingleplayerServer() != null || mc.level == null || !SeedMapConfig.get().seedCrackerAuto) {
			return;
		}
		if (SeedMapConfig.get().seeds.containsKey(GenContextProvider.serverKey()) || seedMap.seedFinder.isRunning()) {
			return;
		}
		Long structureSeed = singleStructureSeed();
		if (structureSeed == null || structureSeed.equals(lastStructureSeed)) {
			return;
		}
		lastStructureSeed = structureSeed;
		SeedMapClient.LOGGER.info("SeedCrackerX found structure seed {}, recovering the world seed from the server hash", structureSeed);
		seedMap.startSeedSearch(structureSeed);
	}

	public static void reset() {
		lastStructureSeed = null;
	}

	private static @Nullable Long singleStructureSeed() {
		try {
			Class<?> main = Class.forName("kaptainwutax.seedcrackerX.SeedCracker");
			Object instance = main.getMethod("get").invoke(null);
			if (instance == null) {
				return null;
			}
			Object storage = main.getMethod("getDataStorage").invoke(instance);
			Object timeMachine = storage.getClass().getMethod("getTimeMachine").invoke(storage);
			Field field = timeMachine.getClass().getField("structureSeeds");
			Object value = field.get(timeMachine);
			if (value instanceof Set<?> seeds) {
				Object[] copy;
				try {
					copy = seeds.toArray();
				} catch (java.util.ConcurrentModificationException busy) {
					return null;
				}
				if (copy.length == 1 && copy[0] instanceof Long seed) {
					return seed;
				}
			}
			return null;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			reflectionFailed = true;
			SeedMapClient.LOGGER.warn("Cannot read SeedCrackerX structure seeds (only full seeds will be taken over)", e);
			return null;
		}
	}
}
