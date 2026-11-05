package com.farmingcalendar;

import com.farmingcalendar.CalendarClient.Message;
import com.farmingcalendar.CalendarClient.Reply;
import com.farmingcalendar.CalendarClient.Result;
import com.farmingcalendar.core.FarmingData;
import com.farmingcalendar.core.FarmingData.Crop;
import com.farmingcalendar.core.FarmingData.PatchDef;
import com.farmingcalendar.core.PatchTypes;
import com.farmingcalendar.core.Planner;
import com.farmingcalendar.core.Planner.Candidate;
import com.farmingcalendar.core.Planner.Op;
import com.farmingcalendar.core.Planner.Sent;
import com.farmingcalendar.core.Planner.Settings;
import com.farmingcalendar.core.Planner.Toggle;
import com.farmingcalendar.core.Predictor;
import com.farmingcalendar.core.Predictor.Prediction;
import com.google.gson.Gson;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.WorldType;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneScapeProfile;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import okhttp3.OkHttpClient;

/**
 * Reads the farming timers that RuneLite's own Time Tracking plugin has stored, and keeps entries on a Google
 * Calendar in step with them through the user's own Apps Script web app. It only reads configuration and sends
 * data to the address the user entered: it never touches the game, adds no menu entries and uses no reflection.
 */
@Slf4j
@PluginDescriptor(
	name = "Farming Calendar Sync",
	description = "Puts your farming patch timers on a Google Calendar, with a phone notification when each is ready. "
		+ "Reads Time Tracking's timers and sends them to a Google Apps Script web app that you set up yourself.",
	tags = {"farming", "calendar", "timers", "time tracking", "google", "notifications", "contract", "trees"}
)
public class FarmingCalendarPlugin extends Plugin
{
	private static final String TT = "timetracking";
	private static final String CONTRACT = "contract";
	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH);
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
	private static final int CHUNK = 12;

	@Inject
	private Client client;
	@Inject
	private ConfigManager configManager;
	@Inject
	private ClientToolbar clientToolbar;
	@Inject
	private OkHttpClient okHttpClient;
	@Inject
	private Gson gson;
	@Inject
	private ScheduledExecutorService executor;

	private FarmingData data;
	private Store store;
	private CalendarClient calendar;
	private FarmingCalendarPanel panel;
	private NavigationButton navigation;

	private final Object scheduleLock = new Object();
	private final Object runLock = new Object();
	private ScheduledFuture<?> pending;
	private boolean pendingForce;
	private ScheduledFuture<?> periodic;
	private volatile boolean leagues;
	private volatile String status = "";

	@Override
	protected void startUp() throws Exception
	{
		data = FarmingData.load(gson);
		store = new Store(configManager, gson);
		calendar = new CalendarClient(okHttpClient, gson);
		panel = new FarmingCalendarPanel(this, data);

		BufferedImage icon = ImageUtil.loadImageResource(FarmingCalendarPlugin.class, "panel_icon.png");
		navigation = NavigationButton.builder().tooltip("Farming Calendar").icon(icon).priority(8).panel(panel).build();
		clientToolbar.addNavigation(navigation);

		// a safety net: retries after a failed send and drops entries whose "delete after" time has passed
		periodic = executor.scheduleWithFixedDelay(() -> evaluate(false), 120, 300, TimeUnit.SECONDS);
		evaluateSoon(false, 5);
	}

	@Override
	protected void shutDown()
	{
		clientToolbar.removeNavigation(navigation);
		synchronized (scheduleLock)
		{
			if (pending != null)
			{
				pending.cancel(false);
				pending = null;
			}
		}
		if (periodic != null)
		{
			periodic.cancel(false);
			periodic = null;
		}
		panel = null;
		navigation = null;
	}

	// ---- events

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!TT.equals(event.getGroup()))
		{
			return;
		}
		String key = event.getKey();
		if (key == null)
		{
			return;
		}
		// patch timers are "<region>.<varbit>"; the others change what a timer means or which crop is the contract
		if (key.matches("\\d+\\.\\d+") || key.equals(CONTRACT) || key.startsWith("farmTickOffset"))
		{
			evaluateSoon(false, 4);
			FarmingCalendarPanel p = panel;
			if (p != null && key.equals(CONTRACT))
			{
				p.refreshLater();
			}
		}
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		if (event.getNewProfile() != null)
		{
			store.set("lastProfile", event.getNewProfile());
			FarmingCalendarPanel p = panel;
			if (p != null)
			{
				p.refreshLater();
			}
			evaluateSoon(false, 8);
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			leagues = client.getWorldType().contains(WorldType.SEASONAL) && !client.getWorldType().contains(WorldType.DEADMAN);
			evaluateSoon(false, 10);
		}
	}

	// ---- what the panel uses

	FarmingData data()
	{
		return data;
	}

	Store store()
	{
		return store;
	}

	String status()
	{
		return status;
	}

	/** The account the switches apply to: the one logged in, or else the last one seen. */
	String activeProfile()
	{
		String key = configManager.getRSProfileKey();
		if (key != null)
		{
			if (!key.equals(store.lastProfile()))
			{
				store.set("lastProfile", key);
			}
			return key;
		}
		return store.lastProfile();
	}

	Toggle toggle(String id)
	{
		return store.toggle(activeProfile(), id);
	}

	/** Applies several switches at once and syncs once. */
	void setToggles(Map<String, Toggle> changes)
	{
		String profile = activeProfile();
		if (profile == null)
		{
			setStatus("Log in once so the plugin knows which account these switches belong to.");
			return;
		}
		for (Map.Entry<String, Toggle> e : changes.entrySet())
		{
			store.setToggle(profile, e.getKey(), e.getValue());
		}
		evaluateSoon(false, 2);
	}

	/** Text for a row's tooltip: what the time tracker knows about this patch. */
	String describe(String id)
	{
		String profile = activeProfile();
		if (profile == null)
		{
			return "Log in once to see this patch.";
		}
		if (id.equals(CONTRACT))
		{
			Candidate c = contractCandidate(profile, readOffset(profile, "farmTickOffsetPrecision"),
				readOffset(profile, "farmTickOffset"));
			if (c.contractName == null)
			{
				return "No farming contract at the moment.";
			}
			return c.p == null ? c.contractName + ": no matching crop seen in the Farming Guild yet."
				: c.contractName + " on the " + c.where + ": " + describePrediction(c.p);
		}
		PatchDef p = data.patch(id);
		Prediction pred = predict(profile, p);
		return pred == null ? "Time Tracking has not seen this patch yet." : describePrediction(pred);
	}

	String contractLabel()
	{
		String profile = activeProfile();
		if (profile == null)
		{
			return "Contract";
		}
		String name = contractName(profile);
		return name == null ? "Contract: none now" : "Contract: " + name;
	}

	void testConnection()
	{
		executor.execute(() ->
		{
			synchronized (runLock)
			{
				try
				{
					Message m = message("ping");
					if (m == null)
					{
						return;
					}
					Reply r = calendar.post(store.url(), m);
					setStatus(r.ok() ? "Connected. " + nullToEmpty(r.message) : "The script refused: " + nullToEmpty(r.message));
				}
				catch (IOException | RuntimeException e)
				{
					setStatus("Could not connect: " + e.getMessage());
				}
			}
		});
	}

	void sendTestReminder()
	{
		executor.execute(() ->
		{
			synchronized (runLock)
			{
				try
				{
					Message m = message("test");
					if (m == null)
					{
						return;
					}
					long at = Instant.now().getEpochSecond() + 120;
					m.start = at;
					Settings s = store.plannerSettings(null);
					Op op = new Op();
					op.op = "upsert";
					op.id = m.account + ":test";
					op.title = "Farming Calendar Sync test";
					op.start = at;
					op.minutes = 5;
					op.description = "Test entry from the RuneLite Farming Calendar Sync plugin. Safe to delete.";
					op.reminders = testReminders(s);
					m.ops.add(op);
					Reply r = calendar.post(store.url(), m);
					setStatus(r.ok() ? "Test entry placed for " + CLOCK.format(Instant.ofEpochSecond(at).atZone(ZoneId.systemDefault()))
						+ ". Your phone should alert at that time." : "The script refused: " + nullToEmpty(r.message));
				}
				catch (IOException | RuntimeException e)
				{
					setStatus("Test failed: " + e.getMessage());
				}
			}
		});
	}

	/** Test alert: on time, by the method the user picked. */
	private static List<Planner.Reminder> testReminders(Settings s)
	{
		List<Planner.Reminder> out = new ArrayList<>();
		if (s.popup || !s.email)
		{
			out.add(new Planner.Reminder("popup", 0));
		}
		if (s.email)
		{
			out.add(new Planner.Reminder("email", 0));
		}
		return out;
	}

	/** Sends every switched-on patch again, even ones that look unchanged. */
	void resync()
	{
		evaluateSoon(true, 0);
	}

	void evaluateSoon(boolean force, long delaySeconds)
	{
		synchronized (scheduleLock)
		{
			pendingForce |= force;
			if (pending != null)
			{
				pending.cancel(false);
			}
			pending = executor.schedule(() ->
			{
				boolean f;
				synchronized (scheduleLock)
				{
					f = pendingForce;
					pendingForce = false;
					pending = null;
				}
				evaluate(f);
			}, delaySeconds, TimeUnit.SECONDS);
		}
	}

	// ---- the sync itself

	private void evaluate(boolean force)
	{
		synchronized (runLock)
		{
			try
			{
				doEvaluate(force);
			}
			catch (RuntimeException e)
			{
				log.warn("Farming Calendar Sync failed", e);
				setStatus("Error: " + e);
			}
		}
	}

	private void doEvaluate(boolean force)
	{
		if (!store.enabled())
		{
			setStatus("Syncing is switched off in Settings.");
			return;
		}
		if (store.url().isEmpty() || store.token().isEmpty())
		{
			setStatus("Open Settings and enter the web app address and secret.");
			return;
		}
		String profile = activeProfile();
		if (profile == null)
		{
			setStatus("Log in once so the plugin knows which account to use.");
			return;
		}
		String account = accountId(profile);
		Settings settings = store.plannerSettings(accountLabel(profile));
		Map<String, Sent> sent = store.sent(account);
		Integer precision = readOffset(profile, "farmTickOffsetPrecision");
		Integer offset = readOffset(profile, "farmTickOffset");
		long now = Instant.now().getEpochSecond();

		Map<String, Toggle> toggles = new HashMap<>();
		List<Candidate> candidates = new ArrayList<>();
		int switchedOn = 0;
		for (PatchDef p : data.patches())
		{
			Toggle t = store.toggle(profile, p.key());
			if (t.sync)
			{
				toggles.put(p.key(), t);
				switchedOn++;
			}
			if (t.sync || sent.containsKey(p.key()))
			{
				Candidate c = new Candidate();
				c.id = p.key();
				c.where = p.where();
				c.kind = PatchTypes.name(p.impl);
				c.healthCheck = data.impl(p.impl).healthCheck;
				c.p = predict(profile, p, precision, offset);
				candidates.add(c);
			}
		}
		Toggle ct = store.toggle(profile, CONTRACT);
		if (ct.sync)
		{
			toggles.put(CONTRACT, ct);
			switchedOn++;
		}
		if (ct.sync || sent.containsKey(CONTRACT))
		{
			candidates.add(contractCandidate(profile, precision, offset));
		}

		List<Op> ops = Planner.plan(candidates, toggles, sent, settings, now, force);
		if (ops.isEmpty())
		{
			setStatus(switchedOn == 0 ? "Nothing is switched on yet. Tick patches below."
				: "Up to date: " + switchedOn + " patch" + (switchedOn == 1 ? "" : "es") + " switched on. "
				+ CLOCK.format(Instant.now().atZone(ZoneId.systemDefault())));
			return;
		}

		int done = 0;
		for (int from = 0; from < ops.size(); from += CHUNK)
		{
			List<Op> chunk = ops.subList(from, Math.min(ops.size(), from + CHUNK));
			Message m = message("sync");
			if (m == null)
			{
				return;
			}
			Map<String, Op> byWireId = new HashMap<>();
			for (Op op : chunk)
			{
				Op wire = wire(op, account);
				byWireId.put(wire.id, op);
				m.ops.add(wire);
			}
			try
			{
				Reply reply = calendar.post(store.url(), m);
				if (!reply.ok())
				{
					setStatus("The script refused: " + nullToEmpty(reply.message));
					return;
				}
				String firstError = null;
				for (Result r : reply.results)
				{
					Op op = byWireId.get(r.id);
					if (op == null)
					{
						continue;
					}
					if ("ok".equals(r.status))
					{
						if (op.isDelete())
						{
							sent.remove(op.id);
						}
						else
						{
							sent.put(op.id, new Sent(op.fp, op.ready));
						}
						done++;
					}
					else if (firstError == null)
					{
						firstError = r.error;
					}
				}
				store.saveSent(account, sent);
				if (firstError != null)
				{
					setStatus("Some entries failed: " + firstError);
					return;
				}
			}
			catch (IOException e)
			{
				setStatus("Could not reach Google (" + e.getMessage() + "). Will retry.");
				return;
			}
		}
		setStatus("Synced " + done + " change" + (done == 1 ? "" : "s") + " at "
			+ CLOCK.format(Instant.now().atZone(ZoneId.systemDefault())) + ".");
	}

	private Message message(String action)
	{
		String profile = activeProfile();
		if (store.url().isEmpty() || store.token().isEmpty())
		{
			setStatus("Open Settings and enter the web app address and secret.");
			return null;
		}
		Message m = new Message();
		m.action = action;
		m.token = store.token();
		m.calendar = store.calendar();
		m.account = profile == null ? "none" : accountId(profile);
		return m;
	}

	private static Op wire(Op op, String account)
	{
		Op w = new Op();
		w.op = op.op;
		w.id = account + ":" + op.id;
		w.title = op.title;
		w.start = op.start;
		w.minutes = op.minutes;
		w.description = op.description == null ? null : op.description + "\n[osrs:" + w.id + "]";
		w.reminders = op.reminders;
		return w;
	}

	// ---- reading Time Tracking's stored timers

	private Integer readOffset(String profile, String key)
	{
		return configManager.getConfiguration(TT, profile, key, int.class);
	}

	private Prediction predict(String profile, PatchDef p)
	{
		return predict(profile, p, readOffset(profile, "farmTickOffsetPrecision"), readOffset(profile, "farmTickOffset"));
	}

	private Prediction predict(String profile, PatchDef p, Integer precision, Integer offset)
	{
		String stored = configManager.getConfiguration(TT, profile, p.key());
		return Predictor.predict(data, p, stored, precision, offset, leagues);
	}

	private String contractName(String profile)
	{
		String item = configManager.getConfiguration(TT, profile, CONTRACT);
		if (item == null)
		{
			return null;
		}
		try
		{
			String key = data.produceKeyByItem(Integer.parseInt(item.trim()));
			return key == null ? null : data.produce(key).name;
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	/**
	 * The contract slot: whichever Farming Guild patch of the right type holds the contract crop, as the
	 * Time Tracking plugin's own contract display chooses it.
	 */
	private Candidate contractCandidate(String profile, Integer precision, Integer offset)
	{
		Candidate c = new Candidate();
		c.id = CONTRACT;
		c.contract = true;
		String item = configManager.getConfiguration(TT, profile, CONTRACT);
		String produceKey = null;
		if (item != null)
		{
			try
			{
				produceKey = data.produceKeyByItem(Integer.parseInt(item.trim()));
			}
			catch (NumberFormatException ignored)
			{
				// treated as no contract
			}
		}
		if (produceKey == null)
		{
			return c; // contractName stays null: no contract
		}
		FarmingData.ProduceDef produce = data.produce(produceKey);
		c.contractName = produce.name;
		c.healthCheck = data.impl(produce.impl).healthCheck;
		boolean anySeen = false;
		PatchDef best = null;
		Prediction bestPrediction = null;
		for (PatchDef p : data.patches())
		{
			if (p.regionId != data.guildRegionId() || !p.impl.equals(produce.impl))
			{
				continue;
			}
			Prediction pred = predict(profile, p, precision, offset);
			if (pred == null)
			{
				continue;
			}
			anySeen = true;
			boolean carries = pred.produceKey.equals(produceKey)
				&& (pred.crop == Crop.GROWING || pred.crop == Crop.HARVESTABLE || pred.crop == Crop.DISEASED);
			if (carries && (bestPrediction == null || pred.done < bestPrediction.done))
			{
				best = p;
				bestPrediction = pred;
			}
		}
		if (best != null)
		{
			c.p = bestPrediction;
			c.carrier = best.key();
			c.where = "Farming Guild " + PatchTypes.name(best.impl).toLowerCase(Locale.ENGLISH) + " patch";
			c.kind = PatchTypes.name(best.impl);
		}
		else if (anySeen)
		{
			c.cleared = true; // the guild patches are known and none grows the contract crop
		}
		return c;
	}

	private String describePrediction(Prediction p)
	{
		String name = p.produce.name;
		switch (p.crop)
		{
			case GROWING:
				if (p.done <= Instant.now().getEpochSecond())
				{
					return name + " has finished growing (ready to check or harvest).";
				}
				return name + " ready " + relative(p.done) + " (" + when(p.done) + ")" + (p.precise ? "" : ", approximate");
			case HARVESTABLE:
				if (p.produce.regrow > 0 && p.done > Instant.now().getEpochSecond())
				{
					return name + " regrown " + relative(p.done) + " (" + when(p.done) + ")";
				}
				return name + " is ready to harvest.";
			case DISEASED:
				return name + " is diseased.";
			case DEAD:
				return name + " is dead.";
			default:
				return p.isWeeds() ? "Empty (weeds)." : "Empty.";
		}
	}

	private static String when(long epoch)
	{
		return WHEN.format(Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()));
	}

	private static String relative(long epoch)
	{
		long secs = epoch - Instant.now().getEpochSecond();
		long mins = Math.max(0, secs / 60);
		return "in " + (mins >= 60 ? (mins / 60) + "h " + (mins % 60) + "m" : mins + "m");
	}

	// ---- account identity

	private String accountLabel(String profile)
	{
		for (RuneScapeProfile p : configManager.getRSProfiles())
		{
			if (p.getKey().equals(profile))
			{
				return p.getDisplayName();
			}
		}
		return null;
	}

	/** A short stable id for the account, so two accounts never share calendar entries. The profile key itself is not sent. */
	static String accountId(String profileKey)
	{
		try
		{
			byte[] d = MessageDigest.getInstance("SHA-256").digest(profileKey.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 4; i++)
			{
				sb.append(String.format("%02x", d[i]));
			}
			return sb.toString();
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException(e);
		}
	}

	private void setStatus(String text)
	{
		status = text;
		FarmingCalendarPanel p = panel;
		if (p != null)
		{
			SwingUtilities.invokeLater(() -> p.showStatus(text));
		}
	}

	private static String nullToEmpty(String s)
	{
		return s == null ? "" : s;
	}
}
