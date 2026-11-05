package com.farmingcalendar;

import com.farmingcalendar.core.Planner.AutoClear;
import com.farmingcalendar.core.Planner.Sent;
import com.farmingcalendar.core.Planner.Settings;
import com.farmingcalendar.core.Planner.Toggle;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.client.config.ConfigManager;

/**
 * Everything the plugin remembers, kept in RuneLite's config under the group "farmingcalendar".
 * Settings are shared by every account; the per-patch switches are kept per RuneScape profile, so a main and an
 * ironman can sync different patches.
 */
final class Store
{
	static final String GROUP = "farmingcalendar";

	/** Shape of the saved "what is already on the calendar" list. */
	private static final class SentFile
	{
		Map<String, Sent> entries = new HashMap<>();
	}

	private final ConfigManager config;
	private final Gson gson;

	Store(ConfigManager config, Gson gson)
	{
		this.config = config;
		this.gson = gson;
	}

	String get(String key, String def)
	{
		String v = config.getConfiguration(GROUP, key);
		return v == null ? def : v;
	}

	void set(String key, String value)
	{
		if (value == null)
		{
			config.unsetConfiguration(GROUP, key);
		}
		else
		{
			config.setConfiguration(GROUP, key, value);
		}
	}

	boolean getBool(String key, boolean def)
	{
		String v = config.getConfiguration(GROUP, key);
		return v == null ? def : Boolean.parseBoolean(v);
	}

	int getInt(String key, int def)
	{
		try
		{
			return Integer.parseInt(get(key, Integer.toString(def)).trim());
		}
		catch (NumberFormatException e)
		{
			return def;
		}
	}

	String url()
	{
		return get("url", "").trim();
	}

	String token()
	{
		return get("token", "").trim();
	}

	String calendar()
	{
		String c = get("calendar", "OSRS Farming").trim();
		return c.isEmpty() ? "OSRS Farming" : c;
	}

	boolean enabled()
	{
		return getBool("enabled", true);
	}

	boolean showAccount()
	{
		return getBool("showAccount", false);
	}

	String lastProfile()
	{
		return get("lastProfile", null);
	}

	/** Parses "0, 10" into {10, 0}: valid alert times, no repeats, longest lead first, at most five. */
	static int[] parseReminders(String text)
	{
		List<Integer> out = new ArrayList<>();
		for (String part : text.split("[,;\\s]+"))
		{
			if (part.isEmpty())
			{
				continue;
			}
			try
			{
				int m = Integer.parseInt(part);
				if (m >= 0 && m <= 40320 && !out.contains(m))
				{
					out.add(m);
				}
			}
			catch (NumberFormatException ignored)
			{
				// skip anything that is not a number
			}
		}
		if (out.isEmpty())
		{
			out.add(0);
		}
		out.sort((a, b) -> b - a);
		int n = Math.min(5, out.size());
		int[] result = new int[n];
		for (int i = 0; i < n; i++)
		{
			result[i] = out.get(i);
		}
		return result;
	}

	Settings plannerSettings(String accountLabel)
	{
		Settings s = new Settings();
		s.reminders = parseReminders(get("reminders", "0"));
		s.popup = getBool("popup", true);
		s.email = getBool("email", false);
		s.eventMinutes = Math.max(1, Math.min(1440, getInt("eventMinutes", 15)));
		try
		{
			s.autoClear = AutoClear.valueOf(get("autoClear", AutoClear.WHEN_DONE.name()));
		}
		catch (IllegalArgumentException e)
		{
			s.autoClear = AutoClear.WHEN_DONE;
		}
		s.deleteAfterHours = Math.max(0, getInt("deleteAfterHours", 0));
		s.remindRegrow = getBool("remindRegrow", true);
		s.accountLabel = showAccount() ? accountLabel : null;
		return s;
	}

	// ---- per-patch switches, stored per profile as "S" (sync) or "SN" (sync and notify)

	Toggle toggle(String profile, String key)
	{
		if (profile == null)
		{
			return new Toggle(false, false);
		}
		String v = config.getConfiguration(GROUP, profile, "p." + key);
		return new Toggle(v != null && v.startsWith("S"), v != null && v.equals("SN"));
	}

	void setToggle(String profile, String key, Toggle t)
	{
		if (profile == null)
		{
			return;
		}
		if (!t.sync)
		{
			// a notification without an entry means nothing; remember the choice only while the entry exists
			config.unsetConfiguration(GROUP, profile, "p." + key);
		}
		else
		{
			config.setConfiguration(GROUP, profile, "p." + key, t.notify ? "SN" : "S");
		}
	}

	// ---- what the calendar already holds, per account

	Map<String, Sent> sent(String account)
	{
		String json = config.getConfiguration(GROUP, "sent." + account);
		if (json == null)
		{
			return new HashMap<>();
		}
		try
		{
			SentFile f = gson.fromJson(json, SentFile.class);
			return f == null || f.entries == null ? new HashMap<>() : f.entries;
		}
		catch (RuntimeException e)
		{
			return new HashMap<>();
		}
	}

	void saveSent(String account, Map<String, Sent> sent)
	{
		SentFile f = new SentFile();
		f.entries = sent;
		config.setConfiguration(GROUP, "sent." + account, gson.toJson(f));
	}
}
