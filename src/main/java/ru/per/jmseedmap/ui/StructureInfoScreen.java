package ru.per.jmseedmap.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;
import ru.per.jmseedmap.core.SeedMap;
import ru.per.jmseedmap.core.StructureDetails;
import ru.per.jmseedmap.core.StructureStyles;
import ru.per.jmseedmap.gen.FoundStructure;

/**
 * One structure: where it is, what is inside ({@link StructureDetails}, filled in when ready) and what to do with it.
 */
public final class StructureInfoScreen extends OptionsSubScreen {
	private final FoundStructure structure;
	private int detailsVersion = -1;
	private boolean detailsShown;

	public StructureInfoScreen(@Nullable Screen parent, FoundStructure structure) {
		super(parent, Minecraft.getInstance().options, Component.literal(StructureStyles.displayName(structure.displayId())));
		this.structure = structure;
	}

	@Override
	protected void init() {
		layout.removeChildren();
		super.init();
	}

	@Override
	protected void addOptions() {
		SeedMap seedMap = SeedMap.get();
		Minecraft mc = Minecraft.getInstance();
		var pos = structure.pos();
		// Where: coordinates, distance and direction, visited — one line.
		MutableComponent where = Component.literal(pos.getX() + " " + pos.getY() + " " + pos.getZ());
		LocalPlayer player = mc.player;
		if (player != null && player.level().dimension().equals(structure.dimension())) {
			double dx = pos.getX() + 0.5 - player.getX();
			double dz = pos.getZ() + 0.5 - player.getZ();
			where.append(" · ").append(Component.translatable("jm_seedmap.pins.distance", Math.round(Math.sqrt(dx * dx + dz * dz)),
				Component.translatable("jm_seedmap.dir." + SeedMap.direction(dx, dz))));
		}
		if (seedMap.isVisited(structure)) {
			where.append(" · ").append(Component.translatable("jm_seedmap.label.visited"));
		}
		list.addBig(line(where));

		StructureDetails.Details details = seedMap.details.get(structure);
		detailsShown = details != null;
		list.addSmall(
			Button.builder(Component.translatable("jm_seedmap.menu.target"), b -> {
				seedMap.setTarget(structure);
				onClose();
			}).build(),
			Button.builder(Component.translatable(seedMap.isPinned(structure) ? "jm_seedmap.menu.unpin" : "jm_seedmap.menu.pin",
				StructureStyles.displayName(structure.displayId())), b -> {
				seedMap.setPinned(structure, !seedMap.isPinned(structure));
				rebuildWidgets();
			}).build());
		list.addSmall(
			Button.builder(Component.translatable(seedMap.isVisited(structure) ? "jm_seedmap.menu.unvisit" : "jm_seedmap.menu.visit"), b -> {
				seedMap.setVisited(structure, !seedMap.isVisited(structure));
				rebuildWidgets();
			}).build(),
			Button.builder(Component.translatable("jm_seedmap.menu.copy"),
				b -> mc.keyboardHandler.setClipboard(pos.getX() + " " + pos.getY() + " " + pos.getZ())).build());
		if (details != null && details.portalRoom() != null) {
			var room = details.portalRoom();
			list.addSmall(
				Button.builder(Component.translatable("jm_seedmap.info.target_portal_room"), b -> {
					seedMap.setTarget(structure.dimension(), room, structure.displayId(),
						Component.translatable("jm_seedmap.info.portal_room_name").getString(), StructureStyles.style(structure.displayId()).color());
					onClose();
				}).build(),
				Button.builder(Component.translatable("jm_seedmap.info.copy_portal_room"),
					b -> mc.keyboardHandler.setClipboard(room.getX() + " " + room.getY() + " " + room.getZ())).build());
		}

		list.addHeader(Component.translatable("jm_seedmap.info.inside"));
		if (details == null) {
			list.addBig(line(Component.translatable("jm_seedmap.details.loading")));
		} else {
			for (Component text : details.lines()) {
				list.addBig(line(text));
			}
		}
	}

	/** A full-width read-only line. */
	private static Button line(Component text) {
		Button button = Button.builder(text, b -> { }).width(310).build();
		button.active = false;
		button.setTooltip(net.minecraft.client.gui.components.Tooltip.create(text));
		return button;
	}

	@Override
	public void tick() {
		super.tick();
		int version = SeedMap.get().details.version();
		if (!detailsShown && version != detailsVersion) {
			detailsVersion = version;
			if (SeedMap.get().details.get(structure) != null) {
				rebuildWidgets();
			}
		}
	}
}
