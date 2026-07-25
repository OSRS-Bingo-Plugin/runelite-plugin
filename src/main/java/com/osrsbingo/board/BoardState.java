package com.osrsbingo.board;

import com.osrsbingo.BingoConfig;
import com.osrsbingo.api.ApiFailure;
import com.osrsbingo.api.ApiOutcome;
import com.osrsbingo.api.BingoApiClient;
import com.osrsbingo.api.BoardResponse;
import com.osrsbingo.api.BoardStatus;
import com.osrsbingo.api.BoardTile;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Single source of truth for board data and connection state. Everything else
 * in the plugin reads from here and never caches the answer, so there is no
 * second copy of "are we connected" to drift out of sync.
 *
 * <p>Polls the cheap /status endpoint and fetches the full board only when the
 * opaque revision token changes, plus a periodic safety refetch.
 */
@Slf4j
public class BoardState
{
	/** Full board refetch interval regardless of revision (spec §6). */
	private static final long SAFETY_REFETCH_MS = 600_000L;

	private final BingoApiClient api;
	private final ScheduledExecutorService executor;
	private final BingoConfig config;
	private final BooleanSupplier loggedIn;
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

	private volatile ConnectionState state = ConnectionState.NO_CODE;
	private volatile BoardResponse board;
	/**
	 * The board code {@link #board} was actually fetched with. Not the same as
	 * {@code config.boardCode()}: ConfigManager writes the new value before it
	 * posts ConfigChanged, so between those two moments config already reads the
	 * new code while the cached — and only validated — board is still the old
	 * one. Reporting must use this, or a drop filtered by board A's item set
	 * gets submitted to board B.
	 */
	private volatile String cachedBoardCode;
	private volatile int pendingCount;
	private volatile Set<Integer> acceptedItemIds = Collections.emptySet();

	private String lastRevision;
	private long lastFullFetchAt;
	private int consecutiveFailures;
	private boolean forceBoardFetch;

	/**
	 * Bumped by every board-code change / explicit refresh. An in-flight
	 * {@link #pollOnce()} captures the value at entry and, before writing any
	 * result of a blocking call into shared state, confirms it is still
	 * current — otherwise the response belongs to a superseded board code (or
	 * a superseded refresh) and is discarded silently. This does not depend
	 * on the executor being single-threaded.
	 */
	private final AtomicLong generation = new AtomicLong();

	/**
	 * Guards read-cancel-reassign of {@link #pollTask} so racing callers
	 * (tick() rescheduling itself, requestRefresh()/refreshSoon() from the
	 * client thread or EDT, stop()) cannot each observe the same future and
	 * each schedule a replacement, which would spawn a second self-
	 * perpetuating poll chain. Never held across a blocking HTTP call.
	 */
	private final Object scheduleLock = new Object();
	private volatile boolean terminated;

	private ScheduledFuture<?> pollTask;
	/** Deadline of the pending {@link #pollTask}, so a periodic reschedule never
	 * clobbers an already-pending sooner poll (e.g. a Refresh's immediate one). */
	private long nextTickDeadlineNanos;

	// NOT @Inject: BingoApiClient and BooleanSupplier have no Guice bindings.
	// BingoPlugin constructs this in startUp().
	public BoardState(BingoApiClient api, ScheduledExecutorService executor, BingoConfig config,
		BooleanSupplier loggedIn)
	{
		this.api = api;
		this.executor = executor;
		this.config = config;
		this.loggedIn = loggedIn;
	}

	public void start()
	{
		terminated = false;
		applySignal(currentCode().isEmpty() ? BoardSignal.CODE_BLANK : BoardSignal.CODE_CHANGED);
		scheduleNext(0L);
	}

	public void stop()
	{
		synchronized (scheduleLock)
		{
			terminated = true;
			if (pollTask != null)
			{
				pollTask.cancel(false);
				pollTask = null;
			}
		}
	}

	public ConnectionState getState()
	{
		return state;
	}

	public BoardResponse getBoard()
	{
		return board;
	}

	/**
	 * @return the board code the currently-cached board was fetched with, or
	 *         null when nothing validated is cached. Callers that are about to
	 *         report a drop MUST use this and must not report when it is null.
	 */
	public String getCachedBoardCode()
	{
		return cachedBoardCode;
	}

	public int getPendingCount()
	{
		return pendingCount;
	}

	public boolean canReport()
	{
		return state.canReport() && !acceptedItemIds.isEmpty();
	}

	/** Immutable union of every tile's accepted item ids. Empty when no board. */
	public Set<Integer> acceptedItemIds()
	{
		return acceptedItemIds;
	}

	public void addListener(Runnable listener)
	{
		listeners.add(listener);
	}

	/**
	 * Removes a previously-registered listener. Null-safe and idempotent — a
	 * caller's {@code shutDown()} may run when {@code startUp()} only
	 * partially completed, or may be called more than once, and must not
	 * throw either way. Removing a listener that was never added (or already
	 * removed) is simply a no-op, exactly like {@link List#remove(Object)}.
	 */
	public void removeListener(Runnable listener)
	{
		if (listener != null)
		{
			listeners.remove(listener);
		}
	}

	public void requestRefresh()
	{
		generation.incrementAndGet();
		applySignal(currentCode().isEmpty() ? BoardSignal.CODE_BLANK : BoardSignal.REFRESH_REQUESTED);
		forceBoardFetch = true;
		scheduleNext(0L);
	}

	public void onBoardCodeChanged()
	{
		generation.incrementAndGet();
		board = null;
		cachedBoardCode = null;
		acceptedItemIds = Collections.emptySet();
		lastRevision = null;
		lastFullFetchAt = 0L;
		consecutiveFailures = 0;
		applySignal(currentCode().isEmpty() ? BoardSignal.CODE_BLANK : BoardSignal.CODE_CHANGED);
		scheduleNext(0L);
	}

	/**
	 * Feeds the outcome of a drop POST back into the state machine (spec §9).
	 * Without this the plugin keeps polling — and keeps reporting — against a
	 * board the backend has just rejected, and every remaining queued drop
	 * fires at it too.
	 *
	 * <ul>
	 *   <li>404 / 401 / 403: the code is dead. Same terminal INVALID_CODE a
	 *       failed status poll produces, which also stops polling.</li>
	 *   <li>409: the event is no longer ACTIVE. Refetch status now rather than
	 *       waiting out the 30s poll interval.</li>
	 *   <li>anything else: a per-drop problem, not a board problem.</li>
	 * </ul>
	 *
	 * <p>Only for the board the drop was actually sent to. The queue retries a
	 * drop for up to an hour, so its 404 can arrive long after the player has
	 * switched codes — and applied blind it would drive the freshly-loaded,
	 * perfectly valid new board to terminal INVALID_CODE, stopping polling and
	 * reporting until the plugin is toggled. Same staleness the {@link
	 * #generation} counter guards on the poll path; here the drop's own code is
	 * matched against the cached one instead, since a rejection is not tied to
	 * any particular poll.
	 *
	 * <p>Called from the submission executor, never the client thread.
	 *
	 * @param boardCode the code the drop was submitted under. A credential: it is
	 *                  only ever compared, never logged or reported.
	 */
	public void onSubmissionRejected(String boardCode, ApiFailure failure)
	{
		if (failure == null)
		{
			return;
		}
		// Not currentCode(): config can already hold a code whose board has not
		// been fetched (let alone validated) yet — cachedBoardCode is the code
		// the live board, and therefore this state machine, belongs to.
		if (boardCode == null || !boardCode.equals(cachedBoardCode))
		{
			return;
		}
		switch (failure.getKind())
		{
			case NOT_FOUND:
			case UNAUTHORIZED:
				applySignal(BoardSignal.NOT_FOUND);
				notifyListeners();
				break;
			case EVENT_NOT_ACTIVE:
				refreshSoon();
				break;
			default:
				break;
		}
	}

	/** Force a full board fetch on the next tick — used after a drop lands. */
	public void refreshSoon()
	{
		forceBoardFetch = true;
		scheduleNext(0L);
	}

	private String currentCode()
	{
		String code = config.boardCode();
		return code == null ? "" : code.trim();
	}

	private void scheduleNext(long delayMs)
	{
		synchronized (scheduleLock)
		{
			if (terminated)
			{
				return;
			}
			long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMs);
			// If a poll is already pending sooner-or-equal, keep it. This lets a
			// Refresh's immediate poll survive a concurrent periodic reschedule
			// from a finishing tick() — without it, clicking Refresh could do
			// nothing until the next 30s/5min poll.
			// Subtraction, not `<=`, so the comparison is safe across nanoTime()'s
			// wrap boundary.
			if (pollTask != null && !pollTask.isDone() && nextTickDeadlineNanos - deadline <= 0L)
			{
				return;
			}
			if (pollTask != null)
			{
				pollTask.cancel(false);
			}
			nextTickDeadlineNanos = deadline;
			pollTask = executor.schedule(this::tick, delayMs, TimeUnit.MILLISECONDS);
		}
	}

	private void tick()
	{
		// This task is now running: free the slot so a concurrent Refresh and the
		// periodic reschedule below compete on an empty pollTask rather than each
		// seeing this (still-running) future as the pending one.
		synchronized (scheduleLock)
		{
			pollTask = null;
		}
		try
		{
			pollOnce();
		}
		catch (RuntimeException e)
		{
			log.warn("Bingo poll failed unexpectedly", e);
		}
		finally
		{
			long interval = BoardTransitions.pollIntervalMs(state, consecutiveFailures);
			if (interval > 0L)
			{
				scheduleNext(interval);
			}
		}
	}

	private void pollOnce()
	{
		String code = currentCode();
		if (code.isEmpty())
		{
			applySignal(BoardSignal.CODE_BLANK);
			return;
		}
		if (state.isStopped())
		{
			return;
		}
		// Never poll while logged out — spec §6.
		if (!loggedIn.getAsBoolean())
		{
			return;
		}

		// Captured before the blocking call so a mid-flight board-code change
		// or explicit refresh (which bump this) is detected on return, and the
		// stale response is discarded instead of overwriting newer state.
		long myGeneration = generation.get();

		ApiOutcome<BoardStatus> outcome = api.fetchStatus(code);

		if (generation.get() != myGeneration)
		{
			return;
		}

		if (outcome.isFailed())
		{
			handleFailure(outcome.getFailure());
			return;
		}
		if (!outcome.isOk())
		{
			return;
		}

		consecutiveFailures = 0;
		BoardStatus status = outcome.getValue();
		pendingCount = status.getPendingCount();
		applySignal(signalFor(status.getEventStatus()));

		if (state.isStopped())
		{
			notifyListeners();
			return;
		}

		boolean revisionChanged = !status.getRevision().equals(lastRevision);
		boolean safetyDue = System.currentTimeMillis() - lastFullFetchAt >= SAFETY_REFETCH_MS;
		if (revisionChanged || safetyDue || forceBoardFetch || board == null)
		{
			fetchBoard(code, status.getRevision(), myGeneration);
		}
		notifyListeners();
	}

	private void fetchBoard(String code, String revision, long myGeneration)
	{
		ApiOutcome<BoardResponse> outcome = api.fetchBoard(code);

		if (generation.get() != myGeneration)
		{
			return;
		}

		if (outcome.isFailed())
		{
			handleFailure(outcome.getFailure());
			return;
		}
		BoardResponse fetched = outcome.getValue();
		if (!isUsable(fetched))
		{
			// A 200 whose body is missing required fields is a failed fetch, not
			// a new board: assigning it would NPE on every subsequent poll, every
			// overlay frame, and on the EDT during panel rebuild. Keep the last
			// good cache, do not advance lastRevision, and back off like any
			// other failure so the next poll retries.
			log.warn("Bingo board response was missing required fields; keeping the cached board");
			handleFailure(ApiFailure.of(ApiFailure.Kind.SERVER_ERROR, 0));
			return;
		}
		forceBoardFetch = false;
		lastFullFetchAt = System.currentTimeMillis();
		lastRevision = revision;
		board = fetched;
		// Set together with the board: these two must never disagree, or a drop
		// filtered by this board's items could be posted to a different code.
		cachedBoardCode = code;
		acceptedItemIds = unionItemIds(board);
	}

	/**
	 * Everything downstream — hasActiveCache(), the overlay, the panel, the
	 * needs list — dereferences these without checking. They are non-optional in
	 * the backend's schema, so a response missing one is a broken response, not
	 * a board.
	 */
	private static boolean isUsable(BoardResponse candidate)
	{
		return candidate != null
			&& candidate.getEvent() != null
			&& candidate.getTeam() != null
			&& candidate.getTiles() != null;
	}

	private void handleFailure(ApiFailure failure)
	{
		// UNAUTHORIZED is as terminal as NOT_FOUND: a revoked or wrong-event
		// board code never starts working again on its own, so treating it as a
		// network blip would leave the panel "Reconnecting" forever while every
		// drop failed permanently.
		if (failure.getKind() == ApiFailure.Kind.NOT_FOUND
			|| failure.getKind() == ApiFailure.Kind.UNAUTHORIZED)
		{
			applySignal(BoardSignal.NOT_FOUND);
			notifyListeners();
			return;
		}
		consecutiveFailures++;
		applySignal(BoardSignal.NETWORK_ERROR);
		notifyListeners();
	}

	private static BoardSignal signalFor(String eventStatus)
	{
		if ("ACTIVE".equals(eventStatus))
		{
			return BoardSignal.STATUS_ACTIVE;
		}
		if ("ARCHIVED".equals(eventStatus))
		{
			return BoardSignal.STATUS_ARCHIVED;
		}
		return BoardSignal.STATUS_DRAFT;
	}

	private void applySignal(BoardSignal signal)
	{
		ConnectionState previous = state;
		state = BoardTransitions.next(previous, signal, hasActiveCache());
		if (previous != state)
		{
			notifyListeners();
		}
	}

	/**
	 * A cache only permits offline reporting when it was captured while the
	 * event was ACTIVE — a DRAFT board must never become reportable by going
	 * offline.
	 */
	private boolean hasActiveCache()
	{
		BoardResponse cached = board;
		return cached != null && "ACTIVE".equals(cached.getEvent().getStatus());
	}

	private static Set<Integer> unionItemIds(BoardResponse board)
	{
		if (board == null || board.getTiles() == null)
		{
			return Collections.emptySet();
		}
		Set<Integer> ids = new HashSet<>();
		for (BoardTile tile : board.getTiles())
		{
			if (tile.getItemIds() != null)
			{
				ids.addAll(tile.getItemIds());
			}
		}
		return Collections.unmodifiableSet(ids);
	}

	private void notifyListeners()
	{
		for (Runnable listener : listeners)
		{
			try
			{
				listener.run();
			}
			catch (RuntimeException e)
			{
				log.warn("Bingo listener failed", e);
			}
		}
	}
}
