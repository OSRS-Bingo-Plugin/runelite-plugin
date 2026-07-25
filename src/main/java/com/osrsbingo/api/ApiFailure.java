package com.osrsbingo.api;

import lombok.Value;

@Value
public class ApiFailure
{
	public enum Kind
	{
		NOT_FOUND,
		/**
		 * 401/403 — the board code was rejected outright (revoked, or belongs
		 * to another event). Terminal, exactly like {@link #NOT_FOUND}: no
		 * amount of retrying or waiting makes a revoked credential work.
		 */
		UNAUTHORIZED,
		EVENT_NOT_ACTIVE,
		VALIDATION,
		PAYLOAD_TOO_LARGE,
		RATE_LIMITED,
		SERVER_ERROR,
		/**
		 * The request demonstrably never reached the server (connection
		 * refused, DNS failure, connection reset before the request was
		 * written). Safe to retry.
		 */
		NETWORK,
		/**
		 * The request was written but no response came back in time. The server
		 * may already have processed it. The drops endpoint is not idempotent
		 * and the frozen contract offers no idempotency key, so a retry can
		 * create a second PENDING submission for one real drop — an admin
		 * cleanup at best, a disputed prize at worst. Never retried.
		 */
		AMBIGUOUS
	}

	Kind kind;
	int httpCode;
	/** Seconds from a Retry-After header, or 0 when absent. */
	long retryAfterSeconds;

	public static ApiFailure of(Kind kind, int httpCode)
	{
		return new ApiFailure(kind, httpCode, 0L);
	}

	public static ApiFailure network()
	{
		return new ApiFailure(Kind.NETWORK, 0, 0L);
	}

	/** @see Kind#AMBIGUOUS */
	public static ApiFailure ambiguous()
	{
		return new ApiFailure(Kind.AMBIGUOUS, 0, 0L);
	}

	/**
	 * Transient failures are worth retrying; permanent ones never succeed on
	 * a repeat and must be dropped.
	 *
	 * <p>{@link Kind#AMBIGUOUS} is deliberately NOT transient even though it is
	 * a transport-level failure: retrying it risks a duplicate submission,
	 * which is worse than losing the automatic report (the screenshot is on
	 * disk and the drop can be submitted by hand).
	 */
	public boolean isTransient()
	{
		return kind == Kind.RATE_LIMITED || kind == Kind.SERVER_ERROR || kind == Kind.NETWORK;
	}
}
