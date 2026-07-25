package com.osrsbingo.api;

/**
 * The result of one HTTP call: a value or a classified failure. Deliberately
 * not exceptions — every caller must handle failure explicitly, because a
 * swallowed failure means a lost drop.
 */
public final class ApiOutcome<T>
{
	private final T value;
	private final ApiFailure failure;

	private ApiOutcome(T value, ApiFailure failure)
	{
		this.value = value;
		this.failure = failure;
	}

	public static <T> ApiOutcome<T> ok(T value)
	{
		return new ApiOutcome<>(value, null);
	}

	public static <T> ApiOutcome<T> failed(ApiFailure failure)
	{
		return new ApiOutcome<>(null, failure);
	}

	public boolean isOk()
	{
		return value != null;
	}

	public boolean isFailed()
	{
		return failure != null;
	}

	public T getValue()
	{
		return value;
	}

	public ApiFailure getFailure()
	{
		return failure;
	}
}
