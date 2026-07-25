package com.osrsbingo.drops;

import com.osrsbingo.BingoConfig;
import com.osrsbingo.board.BoardState;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.http.api.loottracker.LootRecordType;

/**
 * Filters RuneLite loot events down to drops that belong on the team's board.
 *
 * <p>Runs on the client thread: it reads immutable values into {@link DropEvent}
 * and hands them to a sink. No network, no disk, no blocking.
 *
 * <p>{@link LootRecordType#PLAYER} is never reported. Unlike NPC and EVENT loot,
 * a player kill can be staged between two cooperating accounts carrying any
 * item, so including it would undermine the plugin's anti-spoof guarantee.
 */
public class LootHandler
{
	private final BoardState boardState;
	private final BingoConfig config;
	private final Supplier<String> rsnSupplier;
	private final Consumer<List<DropEvent>> sink;
	private final AtomicLong sequence = new AtomicLong();

	public LootHandler(BoardState boardState, BingoConfig config, Supplier<String> rsnSupplier,
		Consumer<List<DropEvent>> sink)
	{
		this.boardState = boardState;
		this.config = config;
		this.rsnSupplier = rsnSupplier;
		this.sink = sink;
	}

	public void onLootReceived(LootReceived event)
	{
		if (!boardState.canReport() || !isEnabled(event.getType()))
		{
			return;
		}
		String rsn = rsnSupplier.get();
		if (rsn == null || rsn.isEmpty())
		{
			return;
		}
		if (event.getItems() == null || event.getItems().isEmpty())
		{
			return;
		}

		Set<Integer> accepted = boardState.acceptedItemIds();
		long seq = sequence.incrementAndGet();
		List<DropEvent> drops = new ArrayList<>();
		for (ItemStack item : event.getItems())
		{
			if (item.getId() <= 0 || item.getQuantity() <= 0)
			{
				continue;
			}
			if (!accepted.contains(item.getId()))
			{
				continue;
			}
			drops.add(new DropEvent(item.getId(), item.getQuantity(), rsn, seq));
		}

		if (!drops.isEmpty())
		{
			sink.accept(drops);
		}
	}

	private boolean isEnabled(LootRecordType type)
	{
		if (type == null)
		{
			return false;
		}
		switch (type)
		{
			case NPC:
				return config.reportNpcLoot();
			case EVENT:
				return config.reportEventLoot();
			case PICKPOCKET:
				return config.reportPickpocket();
			case PLAYER:
			default:
				return false;
		}
	}
}
