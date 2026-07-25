package com.osrsbingo.api;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.osrsbingo.drops.DropEvent;
import java.io.File;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * The only class in the plugin that performs HTTP. Every method is synchronous
 * and MUST be called off the client thread.
 *
 * <p>The board code is a credential: it appears only in the request path and is
 * never logged, never placed in an exception message.
 */
@Slf4j
public class BingoApiClient
{
	private final OkHttpClient httpClient;
	private final Gson gson;
	private final Supplier<String> baseUrlSupplier;

	// NOT @Inject: Supplier<String> has no Guice binding. BingoPlugin builds
	// this by hand so the base URL is read from config on every call.
	public BingoApiClient(OkHttpClient httpClient, Gson gson, Supplier<String> baseUrlSupplier)
	{
		// Derived from the injected client, never built from scratch: the
		// connection pool, dispatcher, timeouts and any RuneLite interceptors are
		// shared. Built once here rather than per request — a per-request
		// newBuilder() would be cheap but pointless churn.
		this.httpClient = httpClient.newBuilder()
			.addNetworkInterceptor(BingoApiClient::markConnected)
			.build();
		this.gson = gson;
		this.baseUrlSupplier = baseUrlSupplier;
	}

	/**
	 * Flips this call's {@link AtomicBoolean} tag the moment OkHttp hands the
	 * request to an established connection.
	 *
	 * <p>This is a NETWORK interceptor, so it runs only after a socket to the
	 * server exists — that is exactly the line between "the request never left
	 * this machine" and "the request may already have been applied". The flag is
	 * carried on the request rather than held in a field so several concurrent
	 * calls cannot see each other's state.
	 */
	private static Response markConnected(Interceptor.Chain chain) throws IOException
	{
		Request request = chain.request();
		AtomicBoolean connected = request.tag(AtomicBoolean.class);
		if (connected != null)
		{
			connected.set(true);
		}
		return chain.proceed(request);
	}

	public ApiOutcome<BoardStatus> fetchStatus(String boardCode)
	{
		HttpUrl url = buildUrl(boardCode, "status");
		if (url == null)
		{
			return ApiOutcome.failed(ApiFailure.of(ApiFailure.Kind.VALIDATION, 0));
		}
		return execute(new Request.Builder().url(url).get().build(), BoardStatus.class);
	}

	/**
	 * Fetches the full board. Unconditional by design: spec §6 gates refetches
	 * on the /status revision token rather than HTTP validators.
	 */
	public ApiOutcome<BoardResponse> fetchBoard(String boardCode)
	{
		HttpUrl url = buildUrl(boardCode, null);
		if (url == null)
		{
			return ApiOutcome.failed(ApiFailure.of(ApiFailure.Kind.VALIDATION, 0));
		}
		return execute(new Request.Builder().url(url).get().build(), BoardResponse.class);
	}

	/**
	 * Builds {base}/api/v1/board/{code}[/{suffix}]. Returns null when the
	 * configured base URL is unparseable.
	 */
	private HttpUrl buildUrl(String boardCode, String suffix)
	{
		String base = baseUrlSupplier.get();
		if (base == null || base.trim().isEmpty())
		{
			return null;
		}
		HttpUrl parsed = HttpUrl.parse(base.trim());
		if (parsed == null)
		{
			return null;
		}
		HttpUrl.Builder builder = parsed.newBuilder()
			.addPathSegment("api")
			.addPathSegment("v1")
			.addPathSegment("board")
			.addPathSegment(boardCode);
		if (suffix != null)
		{
			builder.addPathSegment(suffix);
		}
		return builder.build();
	}

	private <T> ApiOutcome<T> execute(Request request, Class<T> type)
	{
		// One flag per call, read only after the call has finished.
		AtomicBoolean connected = new AtomicBoolean(false);
		Request tagged = request.newBuilder().tag(AtomicBoolean.class, connected).build();

		try (Response response = httpClient.newCall(tagged).execute())
		{
			if (!response.isSuccessful())
			{
				return ApiOutcome.failed(classify(response));
			}
			ResponseBody body = response.body();
			if (body == null)
			{
				return ApiOutcome.failed(ApiFailure.of(ApiFailure.Kind.SERVER_ERROR, response.code()));
			}
			T parsed = gson.fromJson(body.string(), type);
			if (parsed == null)
			{
				return ApiOutcome.failed(ApiFailure.of(ApiFailure.Kind.SERVER_ERROR, response.code()));
			}
			return ApiOutcome.ok(parsed);
		}
		catch (JsonSyntaxException e)
		{
			log.warn("Bingo backend returned unparseable JSON", e);
			return ApiOutcome.failed(ApiFailure.of(ApiFailure.Kind.SERVER_ERROR, 0));
		}
		catch (SocketTimeoutException e)
		{
			// OkHttp throws a bare SocketTimeoutException from the CONNECT phase
			// too (RouteException#getFirstConnectException, rethrown once routes
			// are exhausted), and RuneLite's injected client leaves the default
			// 10s connect timeout in place. The two cases need opposite handling,
			// so tell them apart by whether the network interceptor ever ran.
			if (!connected.get())
			{
				// No connection was ever established: the request never left this
				// machine (blackholed SYNs while a router reboots, for instance),
				// so a retry cannot duplicate anything and must happen — losing a
				// rare drop is far worse than an extra PENDING row.
				log.debug("Bingo request timed out before a connection was established");
				return ApiOutcome.failed(ApiFailure.network());
			}
			// The request was already on the wire; only the response is missing.
			// The server may have applied it, so the caller must NOT retry —
			// see ApiFailure.Kind.AMBIGUOUS. No board code in this message.
			log.debug("Bingo request timed out with an unknown outcome");
			return ApiOutcome.failed(ApiFailure.ambiguous());
		}
		catch (IOException e)
		{
			// Connection refused, unknown host, reset before the request landed:
			// the server never saw it, so a retry cannot duplicate anything.
			// No board code in this message — it is a credential.
			log.debug("Bingo request failed: {}", e.getMessage());
			return ApiOutcome.failed(ApiFailure.network());
		}
	}

	/**
	 * Reports one item stack. Sends multipart when an image is supplied and
	 * plain JSON otherwise — the backend accepts either.
	 *
	 * @param image a JPEG file, or null to send no proof
	 */
	public ApiOutcome<DropResult> submitDrop(String boardCode, DropEvent drop, File image)
	{
		HttpUrl url = buildUrl(boardCode, "drops");
		if (url == null)
		{
			return ApiOutcome.failed(ApiFailure.of(ApiFailure.Kind.VALIDATION, 0));
		}

		RequestBody body;
		if (image != null && image.isFile())
		{
			body = new MultipartBody.Builder()
				.setType(MultipartBody.FORM)
				.addFormDataPart("itemId", Integer.toString(drop.getItemId()))
				.addFormDataPart("quantity", Integer.toString(drop.getQuantity()))
				.addFormDataPart("reportedRsn", drop.getRsn())
				.addFormDataPart("image", image.getName(),
					RequestBody.create(MediaType.parse("image/jpeg"), image))
				.build();
		}
		else
		{
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("itemId", drop.getItemId());
			payload.put("quantity", drop.getQuantity());
			payload.put("reportedRsn", drop.getRsn());
			body = RequestBody.create(MediaType.parse("application/json"), gson.toJson(payload));
		}

		return execute(new Request.Builder().url(url).post(body).build(), DropResult.class);
	}

	// Path segments are encoded by HttpUrl.Builder.addPathSegment — do not
	// pre-encode, or the board code arrives double-escaped.

	static ApiFailure classify(Response response)
	{
		int code = response.code();
		switch (code)
		{
			case 401:
			case 403:
				return ApiFailure.of(ApiFailure.Kind.UNAUTHORIZED, code);
			case 404:
				return ApiFailure.of(ApiFailure.Kind.NOT_FOUND, code);
			case 409:
				return ApiFailure.of(ApiFailure.Kind.EVENT_NOT_ACTIVE, code);
			case 400:
				return ApiFailure.of(ApiFailure.Kind.VALIDATION, code);
			case 413:
				return ApiFailure.of(ApiFailure.Kind.PAYLOAD_TOO_LARGE, code);
			case 429:
				return new ApiFailure(ApiFailure.Kind.RATE_LIMITED, code, retryAfter(response));
			default:
				return ApiFailure.of(
					code >= 500 ? ApiFailure.Kind.SERVER_ERROR : ApiFailure.Kind.VALIDATION, code);
		}
	}

	private static long retryAfter(Response response)
	{
		String header = response.header("Retry-After");
		if (header == null)
		{
			return 0L;
		}
		try
		{
			return Math.max(0L, Long.parseLong(header.trim()));
		}
		catch (NumberFormatException e)
		{
			return 0L;
		}
	}
}
