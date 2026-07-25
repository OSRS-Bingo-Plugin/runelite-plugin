package com.osrsbingo.board;

/**
 * The pure connection state machine. No I/O, no clock, no RuneLite types —
 * every fail-closed guarantee in the plugin is decided here and can be tested
 * exhaustively.
 */
public final class BoardTransitions
{
	private static final long READY_POLL_MS = 30_000L;
	private static final long DRAFT_POLL_MS = 300_000L;
	private static final long BACKOFF_BASE_MS = 30_000L;
	private static final long BACKOFF_CAP_MS = 300_000L;

	private BoardTransitions()
	{
	}

	/**
	 * @param hasActiveCache true only when a board is cached AND that cached
	 *                       board's event status was ACTIVE. A DRAFT or
	 *                       ARCHIVED cache must never enable reporting.
	 */
	public static ConnectionState next(ConnectionState current, BoardSignal signal, boolean hasActiveCache)
	{
		// A stopped state (NO_CODE, ARCHIVED, INVALID_CODE) is terminal: the
		// board is dead until the user does something about it. Without this
		// guard a late or stale poll response (e.g. NETWORK_ERROR arriving
		// after the board was already found ARCHIVED) could silently
		// resurrect it into a reporting-capable state. Only an explicit user
		// action — clearing/changing the code or requesting a refresh — may
		// leave a stopped state; every other signal is a no-op.
		if (current.isStopped()
			&& signal != BoardSignal.CODE_BLANK
			&& signal != BoardSignal.CODE_CHANGED
			&& signal != BoardSignal.REFRESH_REQUESTED)
		{
			return current;
		}

		switch (signal)
		{
			case CODE_BLANK:
				return ConnectionState.NO_CODE;
			case CODE_CHANGED:
			case REFRESH_REQUESTED:
				return ConnectionState.LOADING;
			case STATUS_ACTIVE:
				return ConnectionState.READY;
			case STATUS_DRAFT:
				return ConnectionState.NOT_STARTED;
			case STATUS_ARCHIVED:
				return ConnectionState.ARCHIVED;
			case NOT_FOUND:
				return ConnectionState.INVALID_CODE;
			case NETWORK_ERROR:
				return hasActiveCache ? ConnectionState.OFFLINE_CACHED : ConnectionState.UNREACHABLE;
			default:
				return current;
		}
	}

	/**
	 * @return milliseconds until the next status poll, or 0 when this state
	 *         must generate no traffic at all.
	 */
	public static long pollIntervalMs(ConnectionState state, int consecutiveFailures)
	{
		if (state.isStopped())
		{
			return 0L;
		}
		switch (state)
		{
			case READY:
				return READY_POLL_MS;
			case NOT_STARTED:
				return DRAFT_POLL_MS;
			case OFFLINE_CACHED:
			case UNREACHABLE:
				return backoffMs(consecutiveFailures);
			case LOADING:
			default:
				return READY_POLL_MS;
		}
	}

	private static long backoffMs(int consecutiveFailures)
	{
		int exponent = Math.max(0, Math.min(consecutiveFailures, 16));
		long delay = BACKOFF_BASE_MS << exponent;
		if (delay <= 0 || delay > BACKOFF_CAP_MS)
		{
			return BACKOFF_CAP_MS;
		}
		return delay;
	}
}
