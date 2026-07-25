package com.osrsbingo.chat;

import com.osrsbingo.BingoConfig;
import com.osrsbingo.api.ApiFailure;
import java.util.EnumMap;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;

/**
 * In-game feedback, debounced to one message per error class per five minutes
 * so a sustained outage cannot spam the chat box.
 */
public class ChatNotifier
{
	private static final long DEBOUNCE_MS = 300_000L;

	private final ChatMessageManager chatMessageManager;
	private final BingoConfig config;
	private final Map<ApiFailure.Kind, Long> lastSent = new EnumMap<>(ApiFailure.Kind.class);

	@Inject
	public ChatNotifier(ChatMessageManager chatMessageManager, BingoConfig config)
	{
		this.chatMessageManager = chatMessageManager;
		this.config = config;
	}

	public void success(String itemName, int submissionCount)
	{
		if (!config.notifyInChat() || submissionCount <= 0)
		{
			return;
		}
		send(itemName + " logged — " + submissionCount
			+ (submissionCount == 1 ? " tile." : " tiles."));
	}

	public void failure(ApiFailure.Kind kind, String detail)
	{
		if (!config.notifyInChat())
		{
			return;
		}
		long now = System.currentTimeMillis();
		Long previous = lastSent.get(kind);
		if (previous != null && now - previous < DEBOUNCE_MS)
		{
			return;
		}
		lastSent.put(kind, now);
		send(message(kind, detail));
	}

	/**
	 * Reports a drop that will never be delivered automatically — after up to an
	 * hour of retries, or a permanent (non-retryable) rejection. This is
	 * deliberately NOT subject to the per-{@link ApiFailure.Kind} five-minute
	 * debounce that {@link #failure(ApiFailure.Kind, String)} applies: these
	 * events are rare (one per genuinely lost drop), and unlike a transient
	 * connection blip, silently swallowing a second one within five minutes
	 * costs the player a rare drop they never learn they need to submit by
	 * hand. This is a deliberate, documented deviation from spec §8.4's
	 * blanket "one message per error class per five minutes" — losing an
	 * abandonment message silently is worse than the spam it would otherwise
	 * prevent.
	 *
	 * @param itemName the item that was dropped; never null in practice, but
	 *                 handled defensively
	 * @param screenshotPath absolute path to the saved proof screenshot so the
	 *                       player can submit by hand, or null when none was
	 *                       captured (screenshots disabled, or capture failed).
	 *                       Never the board code — a secret credential — which
	 *                       must never appear in this message.
	 */
	public void abandoned(String itemName, String screenshotPath)
	{
		if (!config.notifyInChat())
		{
			return;
		}
		String name = itemName == null || itemName.isEmpty() ? "A bingo drop" : itemName;
		String text = name + " couldn't be submitted automatically after repeated attempts."
			+ (screenshotPath == null
				? " No screenshot was saved — submit it by hand."
				: " Screenshot saved: " + screenshotPath + " — submit it by hand.");
		send(text);
	}

	private static String message(ApiFailure.Kind kind, String detail)
	{
		switch (kind)
		{
			case NOT_FOUND:
				return "Your bingo board code looks wrong or expired — check the plugin settings.";
			case UNAUTHORIZED:
				return "Your bingo board code was rejected — it may have been revoked."
					+ " Ask your event organiser for a new one.";
			case EVENT_NOT_ACTIVE:
				return "This bingo isn't active right now.";
			case AMBIGUOUS:
				// It may or may not have landed. Never resubmitted automatically —
				// a duplicate costs an admin a cleanup — so say so plainly.
				return "A bingo drop timed out and wasn't confirmed. It was NOT resubmitted,"
					+ " in case it already went through. Check the board, then submit by hand"
					+ (detail == null ? " if it's missing." : " if it's missing: " + detail);
			case NETWORK:
			case SERVER_ERROR:
			case RATE_LIMITED:
				return "Couldn't reach the bingo server. Saved a screenshot"
					+ (detail == null ? "." : ": " + detail);
			case PAYLOAD_TOO_LARGE:
			case VALIDATION:
			default:
				return "A bingo drop couldn't be submitted"
					+ (detail == null ? "." : ": " + detail);
		}
	}

	private void send(String text)
	{
		String message = new ChatMessageBuilder()
			.append(ChatColorType.HIGHLIGHT)
			.append("[Bingo] ")
			.append(ChatColorType.NORMAL)
			.append(text)
			.build();

		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(message)
			.build());
	}
}
