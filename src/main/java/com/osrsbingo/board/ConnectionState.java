package com.osrsbingo.board;

/**
 * Every distinct situation the plugin can be in with respect to its board.
 * Reporting is permitted in exactly two of them — see {@link #canReport()}.
 */
public enum ConnectionState
{
	/** No board code configured. */
	NO_CODE,
	/** First fetch in flight; nothing cached yet. */
	LOADING,
	/** Board loaded and the event is ACTIVE. */
	READY,
	/** Was READY, fetches now failing, cached board still usable. */
	OFFLINE_CACHED,
	/** Loaded, but the event is still DRAFT. */
	NOT_STARTED,
	/** Loaded, and the event is ARCHIVED. Terminal. */
	ARCHIVED,
	/** The backend rejected the board code. Terminal. */
	INVALID_CODE,
	/** Never successfully loaded and the backend is unreachable. */
	UNREACHABLE;

	/**
	 * Drops may only be filtered, screenshotted and submitted in these states.
	 * Everything else is fail-closed: no board data means no reporting.
	 */
	public boolean canReport()
	{
		return this == READY || this == OFFLINE_CACHED;
	}

	/**
	 * Stopped states generate no traffic at all and are left only by a board
	 * code change or a manual refresh.
	 */
	public boolean isStopped()
	{
		return this == NO_CODE || this == ARCHIVED || this == INVALID_CODE;
	}
}
