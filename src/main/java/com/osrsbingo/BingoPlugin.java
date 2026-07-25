package com.osrsbingo;

import com.google.gson.Gson;
import com.google.inject.Provides;
import com.osrsbingo.api.BingoApiClient;
import com.osrsbingo.api.DropResult;
import com.osrsbingo.board.BoardState;
import com.osrsbingo.chat.ChatNotifier;
import com.osrsbingo.drops.DropEvent;
import com.osrsbingo.drops.LootHandler;
import com.osrsbingo.drops.SubmissionQueue;
import com.osrsbingo.proof.ScreenshotService;
import com.osrsbingo.ui.BingoInfoOverlay;
import com.osrsbingo.ui.BingoOverlay;
import com.osrsbingo.ui.BingoPanel;
import com.osrsbingo.ui.OverlayVisibility;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import okhttp3.OkHttpClient;

@Slf4j
@PluginDescriptor(
	name = "OSRS Bingo",
	description = "Shows your clan bingo board in-game and auto-logs drops to it",
	tags = {"bingo", "clan", "drops", "loot", "board"}
)
public class BingoPlugin extends Plugin
{
	/** The bingo backend. Hardcoded — the site is private and not user-configurable. */
	private static final String BACKEND_URL = "https://bingo.osrs-clan-tools.com";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private BingoConfig config;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private Gson gson;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ItemManager itemManager;

	@Inject
	private DrawManager drawManager;

	@Inject
	private ChatNotifier chatNotifier;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ConfigManager configManager;

	private BoardState boardState;
	private SubmissionQueue queue;
	private ScreenshotService screenshotService;
	private LootHandler lootHandler;
	private BingoPanel panel;
	private NavigationButton navButton;
	private BingoOverlay overlay;
	private BingoInfoOverlay infoOverlay;
	/**
	 * The exact {@link Runnable} instance registered with {@link
	 * BoardState#addListener}. A method reference like {@code panel::refresh}
	 * creates a NEW object each time it is written, so this must be captured
	 * once here to be removable later — passing a fresh {@code panel::refresh}
	 * to {@link BoardState#removeListener} would never match.
	 */
	private Runnable panelRefreshListener;

	@Override
	protected void startUp()
	{
		BingoApiClient api = new BingoApiClient(okHttpClient, gson, () -> BACKEND_URL);

		// Final locals, deliberately: shutDown() nulls the fields, and a drain
		// pass already in flight on the shared executor would otherwise NPE
		// reading them. The callbacks hold their own references instead.
		final BoardState state = new BoardState(api, executor, config,
			() -> client.getGameState() == GameState.LOGGED_IN);
		final ChatNotifier notifier = chatNotifier;
		boardState = state;

		queue = new SubmissionQueue(api, executor, System::currentTimeMillis,
			(result, itemName) -> onSubmissionSuccess(notifier, state, result, itemName),
			(drop, rejectedBoardCode, itemName, failure, proof) ->
			{
				// Spec §9: a rejection is board news, not just a chat line —
				// 404 kills the code, 409 means refetch status. It is news about
				// the board this drop was actually sent to, though: the queue
				// retries for up to an hour, so a 404 for a code the player has
				// since replaced must not kill the valid board now loaded.
				state.onSubmissionRejected(rejectedBoardCode, failure);
				// Every abandonment must reach the player — unlike failure(), this
				// is NOT debounced (see ChatNotifier#abandoned). Names the
				// screenshot so a lost drop can be submitted by hand. The filename
				// holds a timestamp and the item name, never the board code.
				notifier.abandoned(itemName, proof == null ? null : proof.getAbsolutePath());
			});

		screenshotService = new ScreenshotService(drawManager, executor);
		lootHandler = new LootHandler(boardState, config, this::localPlayerName, this::onDropsCaptured);

		boardState.start();
		queue.start();

		panel = new BingoPanel(boardState, itemManager, clientThread, configManager);
		panelRefreshListener = panel::refresh;
		boardState.addListener(panelRefreshListener);

		BufferedImage icon = ImageUtil.loadImageResource(BingoPlugin.class, "icon.png");
		navButton = NavigationButton.builder()
			.tooltip("OSRS Bingo")
			.icon(icon)
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		overlay = new BingoOverlay(boardState, () -> OverlayVisibility.isVisible(configManager));
		overlayManager.add(overlay);
		infoOverlay = new BingoInfoOverlay(boardState);
		overlayManager.add(infoOverlay);

		log.info("OSRS Bingo started");
	}

	@Override
	protected void shutDown()
	{
		if (boardState != null)
		{
			boardState.removeListener(panelRefreshListener);
			boardState.stop();
			boardState = null;
		}
		panelRefreshListener = null;
		if (queue != null)
		{
			// Cancel the drain loop BEFORE dropping the reference: the executor
			// is RuneLite's shared one, so an uncancelled task keeps POSTing
			// (and calling back) long after the plugin is gone.
			queue.stop();
			if (queue.size() > 0)
			{
				log.info("OSRS Bingo stopped with {} drop(s) still queued", queue.size());
			}
		}
		queue = null;
		lootHandler = null;
		screenshotService = null;
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		panel = null;
		if (overlay != null)
		{
			overlayManager.remove(overlay);
			overlay = null;
		}
		if (infoOverlay != null)
		{
			overlayManager.remove(infoOverlay);
			infoOverlay = null;
		}
		log.info("OSRS Bingo stopped");
	}

	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		if (lootHandler != null)
		{
			lootHandler.onLootReceived(event);
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!BingoConfig.GROUP.equals(event.getGroup()) || boardState == null)
		{
			return;
		}
		if ("boardCode".equals(event.getKey()))
		{
			boardState.onBoardCodeChanged();
		}
	}

	private String localPlayerName()
	{
		Player local = client.getLocalPlayer();
		return local == null ? null : local.getName();
	}

	/**
	 * One capture per loot event, shared by every item in it — the file is
	 * written once and attached to each request.
	 *
	 * <p>Uses {@code invoke} rather than {@code invokeLater}: this is already on
	 * the client thread (called from the LootReceived subscriber), so the frame
	 * request goes out immediately instead of a tick later, while the reward
	 * interface is still on screen.
	 */
	private void onDropsCaptured(List<DropEvent> drops)
	{
		BoardState state = boardState;
		if (state == null)
		{
			return;
		}
		// NOT config.boardCode(): ConfigManager writes the new value before it
		// posts ConfigChanged, so a drop landing in that window would be
		// filtered by the old board's item set and reported to the new board —
		// a board that was never validated. Report only to the code the cached
		// board was actually fetched with, and not at all when there isn't one.
		String boardCode = state.getCachedBoardCode();
		if (boardCode == null || boardCode.isEmpty())
		{
			return;
		}

		// Captured, not read from the fields: the lambda below runs later and
		// shutDown() nulls them.
		final SubmissionQueue submissionQueue = queue;
		final ScreenshotService screenshots = screenshotService;
		if (submissionQueue == null || screenshots == null)
		{
			return;
		}

		int firstItemId = drops.get(0).getItemId();
		// Item names must be read on the client thread. Resolve every distinct
		// item in this loot event — one name per drop, so the chat line for a
		// delivered drop names that drop's item and not a neighbour's.
		clientThread.invoke(() ->
		{
			Map<Integer, String> itemNames = new HashMap<>();
			for (DropEvent drop : drops)
			{
				itemNames.computeIfAbsent(drop.getItemId(), this::resolveItemName);
			}
			String firstItemName = itemNames.get(firstItemId);

			if (!config.attachScreenshots())
			{
				submissionQueue.submit(drops, boardCode, null, itemNames);
				return;
			}
			screenshots.capture(firstItemName,
				(File image) -> submissionQueue.submit(drops, boardCode, image, itemNames));
		});
	}

	/** Client thread only. Never returns null — the name reaches a chat line. */
	private String resolveItemName(int itemId)
	{
		try
		{
			String name = itemManager.getItemComposition(itemId).getName();
			return name == null || name.isEmpty() ? "Drop" : name;
		}
		catch (RuntimeException e)
		{
			log.debug("Could not resolve name for item {}", itemId);
			return "Drop";
		}
	}

	/**
	 * Handles one delivered submission: always notify chat (it self-silences on
	 * a deduped drop), but only refetch the board when the backend actually
	 * created something. A deduped drop ({@code submissionCount == 0}) changed
	 * nothing server-side, so a refetch would be wasted.
	 */
	static void onSubmissionSuccess(ChatNotifier notifier, BoardState state, DropResult result, String itemName)
	{
		notifier.success(itemName, result.getSubmissionCount());
		if (result.getSubmissionCount() >= 1)
		{
			state.refreshSoon();
		}
	}

	@Provides
	BingoConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BingoConfig.class);
	}
}
