package com.osrsbingo.drops;

import lombok.Value;

/**
 * One item from one loot event, captured on the client thread. Immutable so it
 * can cross to the executor without any further game-state reads.
 */
@Value
public class DropEvent
{
	int itemId;
	int quantity;
	String rsn;
	/**
	 * Monotonic per-loot-event counter. Two identical drops from two kills are
	 * genuinely distinct and must both send; a retry of one must not.
	 */
	long sequence;
}
