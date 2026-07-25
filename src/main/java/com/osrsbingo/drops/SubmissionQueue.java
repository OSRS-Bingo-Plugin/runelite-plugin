package com.osrsbingo.drops;

import com.osrsbingo.api.ApiFailure;
import com.osrsbingo.api.ApiOutcome;
import com.osrsbingo.api.BingoApiClient;
import com.osrsbingo.api.DropResult;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Delivers drops with dedupe, request spacing, and patient retry.
 *
 * <p>Retries are deliberately long-lived: drops are rare and valuable, so a
 * router reboot must not cost a Twisted bow. Sends are spaced because the
 * backend's rate limit bucket is per board code and teammates share it.
 *
 * <p>The clock is injected so backoff and give-up are testable without waiting.
 */
@Slf4j
public class SubmissionQueue
{
	private static final long BASE_BACKOFF_MS = 2_000L;
	private static final long MAX_BACKOFF_MS = 300_000L;
	private static final long GIVE_UP_AFTER_MS = 3_600_000L;
	private static final long SEND_SPACING_MS = 400L;
	private static final long DRAIN_INTERVAL_MS = 1_000L;
	/**
	 * Cap on {@link #seen}. Comfortably more distinct drops than any real
	 * session produces, so eviction only ever removes keys far older than
	 * anything still in {@link #pending} — normal-scale dedupe is unaffected.
	 */
	private static final int SEEN_CAP = 10_000;

	/**
	 * Reports a delivered drop. Takes the item name that belongs to THAT drop —
	 * a shared "most recent name" field mis-names every chat line when two
	 * on-board drops are in flight at once.
	 */
	@FunctionalInterface
	public interface SuccessListener
	{
		void onSuccess(DropResult result, String itemName);
	}

	/**
	 * Reports a drop that will never be delivered. Receives the proof file so
	 * the give-up chat line can name it (spec §9): the automatic report is lost,
	 * but the screenshot on disk lets the player submit by hand.
	 *
	 * @param boardCode the code THIS drop was queued under, which after up to an
	 *                  hour of retries need not be the current one. Needed so a
	 *                  rejection is only ever applied to the board it came from.
	 *                  A credential: never log it, never put it in a message.
	 * @param itemName the item name carried on the drop's queue entry (resolved
	 *                 on the client thread at capture time), so the abandonment
	 *                 chat line names the right item even when several drops are
	 *                 in flight at once. Never a shared mutable "most recent
	 *                 name" field.
	 * @param proof the saved screenshot, or null when none was captured
	 */
	@FunctionalInterface
	public interface PermanentFailureListener
	{
		void onPermanentFailure(DropEvent drop, String boardCode, String itemName, ApiFailure failure, File proof);
	}

	private final BingoApiClient api;
	private final ScheduledExecutorService executor;
	private final LongSupplier clockMs;
	private final SuccessListener onSuccess;
	private final PermanentFailureListener onPermanentFailure;

	private final List<QueuedDrop> pending = new ArrayList<>();
	/**
	 * Every drop key ever submitted, so a re-enqueue of the same loot event is
	 * rejected. Bounded — drops are rare but the plugin's lifetime is not, so
	 * an unbounded set here would grow for as long as the client runs. Backed
	 * by a {@link LinkedHashMap} in insertion order, which evicts the OLDEST
	 * key once {@link #SEEN_CAP} is exceeded; only ever touched under this
	 * queue's monitor (see {@link #submit}), because {@code LinkedHashMap} is
	 * not thread-safe.
	 */
	private final Set<String> seen = Collections.newSetFromMap(new LinkedHashMap<String, Boolean>(16, 0.75f, false)
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest)
		{
			return size() > SEEN_CAP;
		}
	});

	/**
	 * Entries with a request outstanding. Identity-keyed (not equals/hashCode)
	 * because {@link QueuedDrop}'s Lombok-generated hashCode changes as its
	 * mutable fields (attempts, nextAttemptAt, dropImage) change, which would
	 * corrupt a regular HashSet while the entry sat in it.
	 */
	private final Set<QueuedDrop> inFlight = Collections.newSetFromMap(new IdentityHashMap<>());

	private long lastSendAt;

	/**
	 * Guards {@link #drainTask} against racing start()/stop() calls. Never held
	 * across a network call — start() and stop() only touch the future.
	 */
	private final Object lifecycleLock = new Object();
	private ScheduledFuture<?> drainTask;
	/**
	 * Set by {@link #stop()}. Checked by {@link #drainOnce()} as well as
	 * cancelling the future, because cancel(false) does not interrupt a pass
	 * that is already running and the executor is shared with the rest of
	 * RuneLite.
	 */
	private volatile boolean stopped;

	public SubmissionQueue(BingoApiClient api, ScheduledExecutorService executor, LongSupplier clockMs,
		SuccessListener onSuccess, PermanentFailureListener onPermanentFailure)
	{
		this.api = api;
		this.executor = executor;
		this.clockMs = clockMs;
		this.onSuccess = onSuccess;
		this.onPermanentFailure = onPermanentFailure;
	}

	public void start()
	{
		synchronized (lifecycleLock)
		{
			if (stopped || drainTask != null)
			{
				return;
			}
			drainTask = executor.scheduleWithFixedDelay(this::drainOnce, DRAIN_INTERVAL_MS,
				DRAIN_INTERVAL_MS, TimeUnit.MILLISECONDS);
		}
	}

	/**
	 * Cancels the drain loop and permanently stops draining. Idempotent and
	 * safe to call without a preceding {@link #start()}.
	 *
	 * <p>Must be called from the plugin's shutDown(): the executor is
	 * RuneLite's shared single-threaded one, so an uncancelled
	 * scheduleWithFixedDelay task outlives the plugin entirely — still POSTing
	 * drops, still writing chat lines, and still calling back into objects the
	 * plugin has already torn down. Toggling the plugin would leave one such
	 * loop per toggle.
	 */
	public void stop()
	{
		synchronized (lifecycleLock)
		{
			stopped = true;
			if (drainTask != null)
			{
				drainTask.cancel(false);
				drainTask = null;
			}
		}
	}

	/**
	 * Enqueues every item from one loot event. All items share the image file —
	 * one capture per loot event, not per item.
	 *
	 * @param itemNames item id to display name, resolved on the client thread by
	 *                  the caller. Each drop keeps its own name so the chat line
	 *                  for a delivered drop names the right item.
	 */
	public synchronized void submit(List<DropEvent> drops, String boardCode, File image,
		Map<Integer, String> itemNames)
	{
		long now = clockMs.getAsLong();
		for (DropEvent drop : drops)
		{
			String itemName = itemNames == null ? null : itemNames.get(drop.getItemId());
			QueuedDrop queued = new QueuedDrop(drop, boardCode,
				itemName == null || itemName.isEmpty() ? "Drop" : itemName, image, now);
			if (!seen.add(queued.key()))
			{
				log.debug("Skipping duplicate bingo drop for item {}", drop.getItemId());
				continue;
			}
			pending.add(queued);
		}
	}

	public synchronized int size()
	{
		return pending.size();
	}

	/**
	 * Attempts at most one send. Package-visible rather than private so tests
	 * can step the queue deterministically instead of sleeping.
	 *
	 * <p>Deliberately NOT {@code synchronized} as a whole: {@link
	 * BingoApiClient#submitDrop} is a blocking HTTP round-trip, and {@link
	 * #submit} — called from the RuneLite client thread once loot events are
	 * wired up — shares this object's monitor. Holding the lock across the
	 * network call would freeze the client for the duration of the request.
	 * Instead the entry to send (if any) is selected and snapshotted under the
	 * lock in {@link #select()}, the network call happens with no lock held,
	 * and the outcome is re-applied under the lock in {@link #applyOutcome}.
	 * The selected entry is marked in-flight for the duration so a concurrent
	 * {@code drainOnce()} cannot pick — and send — it a second time.
	 *
	 * <p>Never throws: this is invoked by {@code
	 * ScheduledExecutorService#scheduleWithFixedDelay}, which silently
	 * cancels all future runs if the task throws once. An unchecked
	 * exception from {@link BingoApiClient#submitDrop} is therefore caught
	 * and treated as a transient (network-like) failure — the entry is kept
	 * and backed off exactly as a {@code NETWORK} failure would be, never
	 * dropped and never left stuck in-flight — rather than a bug in the HTTP
	 * layer costing a player a rare drop or silently halting the whole
	 * queue.
	 */
	void drainOnce()
	{
		if (stopped)
		{
			return;
		}
		try
		{
			drainOnceUnsafe();
		}
		catch (RuntimeException e)
		{
			log.warn("Unexpected error while draining the bingo submission queue", e);
		}
	}

	private void drainOnceUnsafe()
	{
		Selection selection = select();
		if (selection == null)
		{
			return;
		}

		try
		{
			// Give-ups reach BoardState/Swing via onPermanentFailure; invoke outside the lock.
			for (QueuedDrop givenUp : selection.givenUp)
			{
				// getImage(), not effectiveImage(): a 413 retry stops SENDING the
				// file but it is still on disk, and naming it is the whole point.
				onPermanentFailure.onPermanentFailure(givenUp.getDrop(), givenUp.getBoardCode(),
					givenUp.getItemName(), ApiFailure.network(), givenUp.getImage());
			}

			if (selection.entry == null)
			{
				return;
			}

			ApiOutcome<DropResult> outcome;
			try
			{
				outcome = api.submitDrop(selection.boardCode, selection.drop, selection.image);
			}
			catch (RuntimeException e)
			{
				// Never log the board code (a secret credential) or the exception's
				// message/stack trace, which may embed the request URL. Log only the
				// exception type and the item id, and treat the failure as transient.
				log.warn("submitDrop threw {} for item {}; treating as a transient failure",
					e.getClass().getSimpleName(), selection.drop.getItemId());
				applyOutcome(selection.entry, selection.now, ApiOutcome.failed(ApiFailure.network()));
				return;
			}

			Result result = applyOutcome(selection.entry, selection.now, outcome);
			if (result.success != null)
			{
				onSuccess.onSuccess(result.success, result.itemName);
			}
			else if (result.permanentFailureDrop != null)
			{
				onPermanentFailure.onPermanentFailure(result.permanentFailureDrop,
					result.boardCode, result.itemName, result.permanentFailure, result.proof);
			}
		}
		finally
		{
			// Structural guarantee, not a targeted catch: whatever happens above
			// (including a throwing onPermanentFailure callback for a give-up
			// entry collected in the same select() call as selection.entry), the
			// selected entry must never be left permanently marked in-flight — or
			// select() would skip it forever while it stays in pending,
			// stranding the drop. applyOutcome() already clears it on every path
			// it reaches; this is a harmless no-op (Set#remove of an absent
			// element) when that already happened, and the only thing that
			// clears it when a path above throws before reaching applyOutcome().
			if (selection.entry != null)
			{
				clearInFlight(selection.entry);
			}
		}
	}

	private synchronized void clearInFlight(QueuedDrop queued)
	{
		inFlight.remove(queued);
	}

	/**
	 * Under the lock: applies the send-spacing gate, gives up on stale entries,
	 * and picks at most one due, not-already-in-flight entry to send. Marks
	 * that entry in-flight and snapshots everything the network call needs so
	 * the caller can send with no lock held.
	 */
	private synchronized Selection select()
	{
		long now = clockMs.getAsLong();
		List<QueuedDrop> givenUp = new ArrayList<>();
		QueuedDrop toSend = null;

		if (!pending.isEmpty() && now - lastSendAt >= SEND_SPACING_MS)
		{
			Iterator<QueuedDrop> iterator = pending.iterator();
			while (iterator.hasNext())
			{
				QueuedDrop queued = iterator.next();
				if (inFlight.contains(queued))
				{
					// Already being sent by a concurrent drainOnce(); never pick it again.
					continue;
				}
				if (now - queued.getFirstAttemptAt() > GIVE_UP_AFTER_MS)
				{
					iterator.remove();
					givenUp.add(queued);
					continue;
				}
				if (now < queued.getNextAttemptAt())
				{
					continue;
				}

				lastSendAt = now;
				queued.setAttempts(queued.getAttempts() + 1);
				inFlight.add(queued);
				toSend = queued;
				break;
			}
		}

		if (givenUp.isEmpty() && toSend == null)
		{
			return null;
		}

		if (toSend == null)
		{
			return new Selection(givenUp, null, now, null, null, null);
		}
		return new Selection(givenUp, toSend, now, toSend.getBoardCode(), toSend.getDrop(),
			toSend.effectiveImage());
	}

	/**
	 * Under the lock: applies the outcome of a completed send — remove on
	 * success or permanent failure, or update {@code dropImage} /
	 * {@code nextAttemptAt} for a retry — and clears the in-flight marker.
	 */
	private synchronized Result applyOutcome(QueuedDrop queued, long now, ApiOutcome<DropResult> outcome)
	{
		inFlight.remove(queued);

		if (outcome.isOk())
		{
			pending.removeIf(candidate -> candidate == queued);
			return Result.success(outcome.getValue(), queued.getItemName());
		}

		ApiFailure failure = outcome.getFailure();
		if (failure == null)
		{
			pending.removeIf(candidate -> candidate == queued);
			return Result.none();
		}
		if (failure.getKind() == ApiFailure.Kind.PAYLOAD_TOO_LARGE && !queued.isDropImage())
		{
			// Retry once without the proof image rather than losing the drop.
			queued.setDropImage(true);
			queued.setNextAttemptAt(now + BASE_BACKOFF_MS);
			return Result.none();
		}
		if (!failure.isTransient())
		{
			pending.removeIf(candidate -> candidate == queued);
			return Result.permanentFailure(queued.getDrop(), queued.getBoardCode(), queued.getItemName(),
				failure, queued.getImage());
		}

		queued.setNextAttemptAt(now + retryDelayMs(queued, failure));
		return Result.none();
	}

	private long retryDelayMs(QueuedDrop queued, ApiFailure failure)
	{
		if (failure.getRetryAfterSeconds() > 0L)
		{
			return failure.getRetryAfterSeconds() * 1_000L;
		}
		int exponent = Math.max(0, Math.min(queued.getAttempts() - 1, 16));
		long delay = BASE_BACKOFF_MS << exponent;
		if (delay <= 0L || delay > MAX_BACKOFF_MS)
		{
			delay = MAX_BACKOFF_MS;
		}
		// +/-20% jitter so a shared rate-limit bucket is not hammered in lockstep.
		double jitter = 0.8 + (Math.random() * 0.4);
		return (long) (delay * jitter);
	}

	/** Snapshot of what {@link #drainOnce()} should do outside the lock. */
	private static final class Selection
	{
		final List<QueuedDrop> givenUp;
		final QueuedDrop entry;
		final long now;
		final String boardCode;
		final DropEvent drop;
		final File image;

		Selection(List<QueuedDrop> givenUp, QueuedDrop entry, long now, String boardCode, DropEvent drop,
			File image)
		{
			this.givenUp = givenUp;
			this.entry = entry;
			this.now = now;
			this.boardCode = boardCode;
			this.drop = drop;
			this.image = image;
		}
	}

	/** Outcome of {@link #applyOutcome} for {@link #drainOnce()} to report outside the lock. */
	private static final class Result
	{
		final DropResult success;
		/** Name of the item this particular result belongs to. */
		final String itemName;
		final DropEvent permanentFailureDrop;
		/** The code the failed drop was queued under — see PermanentFailureListener. */
		final String boardCode;
		final ApiFailure permanentFailure;
		final File proof;

		private Result(DropResult success, String itemName, DropEvent permanentFailureDrop,
			String boardCode, ApiFailure permanentFailure, File proof)
		{
			this.success = success;
			this.itemName = itemName;
			this.permanentFailureDrop = permanentFailureDrop;
			this.boardCode = boardCode;
			this.permanentFailure = permanentFailure;
			this.proof = proof;
		}

		static Result success(DropResult value, String itemName)
		{
			return new Result(value, itemName, null, null, null, null);
		}

		static Result permanentFailure(DropEvent drop, String boardCode, String itemName,
			ApiFailure failure, File proof)
		{
			return new Result(null, itemName, drop, boardCode, failure, proof);
		}

		static Result none()
		{
			return new Result(null, null, null, null, null, null);
		}
	}
}
