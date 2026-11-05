package com.farmingcalendar;

import com.farmingcalendar.core.Planner.Op;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Talks to the user's own Google Apps Script web app, and nothing else. The address must be a script.google.com
 * address and nothing is sent until the user has entered one.
 */
final class CalendarClient
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	/** What the script is sent. */
	static final class Message
	{
		String token;
		String action;
		String calendar;
		String account;
		long start;
		List<Op> ops = new ArrayList<>();
	}

	static final class Result
	{
		String id;
		String status;
		String error;
	}

	/** What the script answers. */
	static final class Reply
	{
		String status;
		String message;
		List<Result> results = new ArrayList<>();

		boolean ok()
		{
			return "ok".equals(status);
		}
	}

	private final OkHttpClient http;
	private final Gson gson;

	CalendarClient(OkHttpClient http, Gson gson)
	{
		// an entry takes the script a moment, and a resync may carry a dozen
		this.http = http.newBuilder().callTimeout(90, TimeUnit.SECONDS).build();
		this.gson = gson;
	}

	static boolean validUrl(String url)
	{
		return url != null && url.startsWith("https://script.google.com/") && url.contains("/exec");
	}

	Reply post(String url, Message message) throws IOException
	{
		if (!validUrl(url))
		{
			throw new IOException("The web app address must be the https://script.google.com/.../exec link from Deploy.");
		}
		byte[] body = gson.toJson(message).getBytes(StandardCharsets.UTF_8);
		Request request = new Request.Builder().url(url).post(RequestBody.create(JSON, body)).build();
		try (Response response = http.newCall(request).execute())
		{
			String text = response.body() == null ? "" : response.body().string();
			if (!response.isSuccessful())
			{
				throw new IOException("Google answered " + response.code());
			}
			try
			{
				Reply reply = gson.fromJson(text, Reply.class);
				if (reply == null)
				{
					throw new IOException("Empty answer from the script.");
				}
				return reply;
			}
			catch (JsonSyntaxException e)
			{
				// Google sends an HTML page when the deployment is not open to "Anyone" or is not the newest version
				throw new IOException("The script did not answer with data. Check it is deployed as a web app open to Anyone.");
			}
		}
	}
}
