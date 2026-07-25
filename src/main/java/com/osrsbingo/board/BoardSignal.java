package com.osrsbingo.board;

/** Inputs to the connection state machine. */
public enum BoardSignal
{
	/** Config has no board code. */
	CODE_BLANK,
	/** The user changed the board code. */
	CODE_CHANGED,
	/** The user clicked Refresh. */
	REFRESH_REQUESTED,
	/** /status returned eventStatus ACTIVE. */
	STATUS_ACTIVE,
	/** /status returned eventStatus DRAFT. */
	STATUS_DRAFT,
	/** /status returned eventStatus ARCHIVED. */
	STATUS_ARCHIVED,
	/** The backend returned 404 for this board code. */
	NOT_FOUND,
	/** Transport failure, 5xx, or 429. */
	NETWORK_ERROR
}
