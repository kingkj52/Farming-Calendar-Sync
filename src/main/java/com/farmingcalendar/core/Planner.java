package com.farmingcalendar.core;

import com.farmingcalendar.core.FarmingData.Crop;
import com.farmingcalendar.core.Predictor.Prediction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Decides, for every patch the user has switched on, whether the calendar needs a new or changed entry,
 * should lose one, or should be left alone. No RuneLite types in here, so it is tested on its own.
 *
 * <p>The rules follow the way a patch is used: an entry sits at the time the patch finishes, stays on the
 * calendar after that time until the patch has been checked, harvested or cleared, and is replaced (same
 * entry, new time) when the patch is replanted.
 */
public final class Planner
{
	private Planner()
	{
	}

	public enum AutoClear
	{
		/** Remove the entry once the patch is checked, harvested, cleared, or dies. */
		WHEN_DONE,
		/** Never remove an entry because of the patch; only replanting replaces it. */
		NEVER
	}

	/** What the user chose for one patch. */
	public static final class Toggle
	{
		public boolean sync;
		public boolean notify;

		public Toggle(boolean sync, boolean notify)
		{
			this.sync = sync;
			this.notify = notify;
		}
	}

	/** What was last sent to the calendar for one entry. Persisted, so unchanged entries are not resent. */
	public static final class Sent
	{
		public String fp;
		public long ready;

		public Sent()
		{
		}

		public Sent(String fp, long ready)
		{
			this.fp = fp;
			this.ready = ready;
		}
	}

	public static final class Settings
	{
		/** Minutes before the entry's start time to alert, 0 meaning exactly on time. */
		public int[] reminders = {0};
		/** Alert as a phone / app notification. */
		public boolean popup = true;
		public boolean email = false;
		public int eventMinutes = 15;
		public AutoClear autoClear = AutoClear.WHEN_DONE;
		/** Remove an entry this many hours after its patch finished, whatever happened to the patch. 0 = never. */
		public int deleteAfterHours = 0;
		public boolean remindRegrow = true;
		/** Account name to put in front of titles, or null. */
		public String accountLabel;
	}

	/** A patch (or the contract slot) as the planner sees it. */
	public static final class Candidate
	{
		public String id;
		public String where;
		public String kind;
		public Prediction p;
		public boolean healthCheck;
		/** The patch is known to hold nothing (the contract slot has no matching crop planted). */
		public boolean cleared;
		public boolean contract;
		/** For the contract slot: the crop's name, or null if there is no contract. */
		public String contractName;
		/** For the contract slot: the id of the guild patch that carries the contract crop, or null. */
		public String carrier;
	}

	/** One alert on an entry, in the Google Calendar API's terms: method "popup" or "email". */
	public static final class Reminder
	{
		public String method;
		public int minutes;

		public Reminder(String method, int minutes)
		{
			this.method = method;
			this.minutes = minutes;
		}
	}

	public static final class Op
	{
		public String op;
		public String id;
		public String title;
		public long start;
		public int minutes;
		public String description;
		public List<Reminder> reminders;
		public transient String fp;
		public transient long ready;

		public boolean isDelete()
		{
			return "delete".equals(op);
		}
	}

	/**
	 * @param toggles per id; patches not in the map are off
	 * @param sent    what the calendar already has, per id
	 * @param force   resend every entry even if it looks unchanged (a "resync")
	 */
	public static List<Op> plan(List<Candidate> candidates, Map<String, Toggle> toggles, Map<String, Sent> sent,
		Settings s, long now, boolean force)
	{
		List<Op> deletes = new ArrayList<>();
		List<Op> upserts = new ArrayList<>();

		// the contract slot wins over the guild patch that carries its crop, so there is one entry, not two
		String suppressed = null;
		for (Candidate c : candidates)
		{
			Toggle t = toggles.get(c.id);
			if (c.contract && c.carrier != null && t != null && t.sync)
			{
				suppressed = c.carrier;
			}
		}

		List<String> handled = new ArrayList<>();
		for (Candidate c : candidates)
		{
			handled.add(c.id);
			Toggle t = toggles.get(c.id);
			Sent prev = sent.get(c.id);
			boolean wanted = t != null && t.sync && !c.id.equals(suppressed);
			Op op = decide(c, t, prev, wanted, s, now);
			if (op == null)
			{
				continue;
			}
			if (op.isDelete())
			{
				if (prev != null)
				{
					deletes.add(op);
				}
			}
			else if (force || prev == null || !op.fp.equals(prev.fp))
			{
				upserts.add(op);
			}
		}
		// entries for ids that are no longer candidates at all (patch switched off): remove them
		for (String id : sent.keySet())
		{
			if (!handled.contains(id))
			{
				Op op = new Op();
				op.op = "delete";
				op.id = id;
				deletes.add(op);
			}
		}
		List<Op> all = new ArrayList<>(deletes);
		all.addAll(upserts);
		return all;
	}

	private static Op decide(Candidate c, Toggle t, Sent prev, boolean wanted, Settings s, long now)
	{
		if (!wanted)
		{
			return prev != null ? delete(c.id) : null;
		}
		if (prev != null && s.deleteAfterHours > 0 && prev.ready > 0 && now > prev.ready + s.deleteAfterHours * 3600L)
		{
			return delete(c.id);
		}
		Prediction p = c.p;
		if (c.cleared || (p != null && (p.isWeeds() || p.crop == Crop.EMPTY || p.crop == Crop.FILLING)))
		{
			return clearedOp(c, prev, s);
		}
		if (p == null)
		{
			// contract slot with no contract at all
			if (c.contract && c.contractName == null)
			{
				return clearedOp(c, prev, s);
			}
			return null; // never seen by the time tracker: nothing to say
		}
		switch (p.crop)
		{
			case DEAD:
				return prev != null ? delete(c.id) : null;
			case DISEASED:
				// no countdown while diseased; keep the old time, flag it
				return prev != null && prev.ready > 0 ? upsert(c, t, s, prev.ready, Kind.DISEASED) : null;
			case GROWING:
				// A patch that has already finished gets no new or moved entry: an existing one stays where it
				// was (visiting a finished patch re-stamps it with the visit time, which must not drag the entry along).
				if (p.done <= 0 || p.done <= now)
				{
					return null;
				}
				return upsert(c, t, s, p.done, Kind.READY);
			case HARVESTABLE:
				if (p.produce.regrow > 0 && p.done > now)
				{
					if (s.remindRegrow)
					{
						return upsert(c, t, s, p.done, Kind.REGROWN);
					}
					return c.healthCheck && s.autoClear == AutoClear.WHEN_DONE && prev != null ? delete(c.id) : null;
				}
				// ready now. A health-checked crop in this state has been checked already
				if (c.healthCheck && s.autoClear == AutoClear.WHEN_DONE)
				{
					return prev != null ? delete(c.id) : null;
				}
				return null; // ready and not yet harvested: leave the entry where it is
			default:
				return null;
		}
	}

	private static Op clearedOp(Candidate c, Sent prev, Settings s)
	{
		return s.autoClear == AutoClear.WHEN_DONE && prev != null ? delete(c.id) : null;
	}

	private static Op delete(String id)
	{
		Op op = new Op();
		op.op = "delete";
		op.id = id;
		return op;
	}

	private enum Kind
	{
		READY, REGROWN, DISEASED
	}

	private static Op upsert(Candidate c, Toggle t, Settings s, long ready, Kind kind)
	{
		Prediction p = c.p;
		String produce = c.contract ? c.contractName : p.produce.name;
		StringBuilder title = new StringBuilder();
		if (s.accountLabel != null && !s.accountLabel.isEmpty())
		{
			title.append('[').append(s.accountLabel).append("] ");
		}
		if (c.contract)
		{
			title.append("Farming contract: ").append(produce);
		}
		else
		{
			title.append(produce);
		}
		switch (kind)
		{
			case READY:
				title.append(" ready");
				break;
			case REGROWN:
				title.append(" regrown");
				break;
			default:
				title.append(" diseased");
				break;
		}
		title.append(" at ").append(c.where);

		StringBuilder d = new StringBuilder();
		d.append(produce).append(" at ").append(c.where).append(" (").append(c.kind).append(")\n");
		if (kind == Kind.DISEASED)
		{
			d.append("Diseased: growth is paused, so this time is the old estimate. Cure it.\n");
		}
		if (!p.precise)
		{
			d.append("Approximate: RuneLite has not learned your farming tick offset yet, so this can be off by up to ")
				.append(tickMinutes(p)).append(" minutes.\n");
		}
		d.append("Kept in step by the Farming Calendar Sync RuneLite plugin.");

		boolean notify = t != null && t.notify;
		List<Reminder> reminders = new ArrayList<>();
		if (notify)
		{
			for (int minutes : s.reminders)
			{
				if (s.popup)
				{
					reminders.add(new Reminder("popup", minutes));
				}
				if (s.email)
				{
					reminders.add(new Reminder("email", minutes));
				}
			}
			// Google Calendar allows five alerts on an entry
			while (reminders.size() > 5)
			{
				reminders.remove(reminders.size() - 1);
			}
		}
		Op op = new Op();
		op.op = "upsert";
		op.id = c.id;
		op.title = title.toString();
		op.start = ready;
		op.minutes = s.eventMinutes;
		op.description = d.toString();
		op.reminders = reminders;
		op.ready = ready;
		StringBuilder fp = new StringBuilder();
		fp.append(op.title).append('|').append(ready / 60).append('|').append(op.minutes).append('|');
		for (Reminder r : reminders)
		{
			fp.append(r.method).append(r.minutes).append(',');
		}
		fp.append('|').append(p.precise);
		op.fp = fp.toString();
		return op;
	}

	private static int tickMinutes(Prediction p)
	{
		return p.crop == Crop.HARVESTABLE ? p.produce.regrow : p.produce.tick;
	}
}
