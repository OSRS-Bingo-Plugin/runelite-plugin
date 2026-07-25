package com.osrsbingo.drops;

import java.io.File;
import lombok.Data;
import lombok.ToString;

/**
 * A drop awaiting delivery. Carries its own board code so a mid-flight code
 * change still delivers to the board the drop was earned on.
 *
 * <p>{@code boardCode} is excluded from the generated {@code toString()} —
 * it is a credential and must never appear in a log line.
 */
@Data
public class QueuedDrop
{
	private final DropEvent drop;
	@ToString.Exclude
	private final String boardCode;
	/**
	 * Resolved on the client thread at capture time and carried with the drop,
	 * so the chat line reports the name of the item that actually succeeded
	 * even when several drops are in flight at once.
	 */
	private final String itemName;
	private final File image;
	private final long firstAttemptAt;

	private int attempts;
	private long nextAttemptAt;
	/** Set after a 413 so the retry omits the proof image. */
	private boolean dropImage;

	public QueuedDrop(DropEvent drop, String boardCode, String itemName, File image, long now)
	{
		this.drop = drop;
		this.boardCode = boardCode;
		this.itemName = itemName;
		this.image = image;
		this.firstAttemptAt = now;
		this.nextAttemptAt = now;
	}

	public File effectiveImage()
	{
		return dropImage ? null : image;
	}

	/** Dedupe identity — see {@link DropEvent#getSequence()}. */
	public String key()
	{
		return drop.getSequence() + ":" + drop.getItemId() + ":" + drop.getQuantity();
	}
}
