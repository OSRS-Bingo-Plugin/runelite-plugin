package com.osrsbingo.ui;

import java.util.List;
import lombok.Value;

/** One row of the "still needed" list. Pure data — no Swing, no RuneLite. */
@Value
public class NeedsRow
{
	String tileId;
	String title;
	int points;
	int current;
	int target;
	boolean pending;
	boolean completeSet;
	/** Item id used for the row's icon; 0 when the tile has no items. */
	int iconItemId;
	List<Integer> acceptedItemIds;
	/** For COMPLETE_SET tiles: accepted ids not yet obtained. Empty otherwise. */
	List<Integer> missingItemIds;
}
