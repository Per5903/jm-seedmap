package ru.per.jmseedmap.map.jm;

import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.common.JourneyMapPlugin;
import ru.per.jmseedmap.SeedMapClient;
import ru.per.jmseedmap.core.SeedMap;

@JourneyMapPlugin(apiVersion = "2.0.0")
public final class SeedMapPlugin implements IClientPlugin {
	@Override
	public void initialize(IClientAPI api) {
		SeedMap.get().addBackend(new JourneyMapBackend(api));
	}

	@Override
	public String getModId() {
		return SeedMapClient.MOD_ID;
	}
}
