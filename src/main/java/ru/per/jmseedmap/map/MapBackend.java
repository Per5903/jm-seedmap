package ru.per.jmseedmap.map;

import ru.per.jmseedmap.core.SeedMap;

/**
 * One map mod integration (JourneyMap, Xaero's Minimap, Xaero's World Map).
 * Only instantiated when that mod is installed.
 */
public interface MapBackend {
	String name();

	/** Every client tick on the render thread. */
	void tick(SeedMap seedMap);
}
