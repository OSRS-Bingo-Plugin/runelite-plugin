package com.osrsbingo.ui;

import com.osrsbingo.api.BoardResponse;
import com.osrsbingo.api.BoardTile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Turns a board response into the panel's rows, split into three buckets:
 * needed, pending review, and completed. Pure and Swing-free so the bucketing,
 * ordering and missing-item derivation are unit-testable.
 */
public final class NeedsListModel
{
	private static final String KIND_ITEM_DROP = "ITEM_DROP";
	private static final String MODE_COMPLETE_SET = "COMPLETE_SET";

	private NeedsListModel()
	{
	}

	/**
	 * Item tiles still to get: incomplete AND not awaiting review, highest
	 * points first. Pending tiles live in {@link #buildPending} instead, so no
	 * tile is ever listed twice.
	 */
	public static List<NeedsRow> buildNeeded(BoardResponse board)
	{
		return collect(board, tile -> !tile.getProgress().isCompleted() && !tile.isPending());
	}

	/** Incomplete item tiles with a submission awaiting an admin. */
	public static List<NeedsRow> buildPending(BoardResponse board)
	{
		return collect(board, tile -> !tile.getProgress().isCompleted() && tile.isPending());
	}

	/** Completed item tiles. */
	public static List<NeedsRow> buildCompleted(BoardResponse board)
	{
		return collect(board, tile -> tile.getProgress().isCompleted());
	}

	private static List<NeedsRow> collect(BoardResponse board, Predicate<BoardTile> filter)
	{
		if (board == null || board.getTiles() == null)
		{
			return Collections.emptyList();
		}
		List<NeedsRow> rows = new ArrayList<>();
		for (BoardTile tile : board.getTiles())
		{
			if (isItemTile(tile) && filter.test(tile))
			{
				rows.add(toRow(tile));
			}
		}
		rows.sort(Comparator.comparingInt(NeedsRow::getPoints).reversed()
			.thenComparing(NeedsRow::getTitle));
		return rows;
	}

	public static int countCompleted(BoardResponse board)
	{
		if (board == null || board.getTiles() == null)
		{
			return 0;
		}
		int count = 0;
		for (BoardTile tile : board.getTiles())
		{
			if (isItemTile(tile) && tile.getProgress().isCompleted())
			{
				count++;
			}
		}
		return count;
	}

	public static int countTotalItemTiles(BoardResponse board)
	{
		if (board == null || board.getTiles() == null)
		{
			return 0;
		}
		int count = 0;
		for (BoardTile tile : board.getTiles())
		{
			if (isItemTile(tile))
			{
				count++;
			}
		}
		return count;
	}

	private static boolean isItemTile(BoardTile tile)
	{
		return KIND_ITEM_DROP.equals(tile.getKind())
			&& tile.getItemIds() != null
			&& !tile.getItemIds().isEmpty();
	}

	private static NeedsRow toRow(BoardTile tile)
	{
		List<Integer> accepted = tile.getItemIds();
		boolean completeSet = MODE_COMPLETE_SET.equals(tile.getCountMode());
		List<Integer> missing = completeSet ? missingItems(tile) : Collections.emptyList();
		int icon = accepted.isEmpty() ? 0 : accepted.get(0);

		return new NeedsRow(
			tile.getId(),
			tile.getTitle(),
			tile.getPoints(),
			tile.getProgress().getCurrent(),
			tile.getProgress().getTarget(),
			tile.isPending(),
			completeSet,
			icon,
			Collections.unmodifiableList(new ArrayList<>(accepted)),
			Collections.unmodifiableList(missing));
	}

	/**
	 * Accepted ids with no approved submission yet. Only meaningful for
	 * COMPLETE_SET — an ANY tile has nothing to be individually missing.
	 */
	private static List<Integer> missingItems(BoardTile tile)
	{
		Set<Integer> obtained = tile.getObtainedItemIds() == null
			? Collections.emptySet()
			: new HashSet<>(tile.getObtainedItemIds());
		List<Integer> missing = new ArrayList<>();
		for (Integer id : tile.getItemIds())
		{
			if (!obtained.contains(id))
			{
				missing.add(id);
			}
		}
		return missing;
	}
}
