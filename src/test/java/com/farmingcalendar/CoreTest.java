package com.farmingcalendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.farmingcalendar.core.FarmingData;
import com.farmingcalendar.core.FarmingData.Crop;
import com.farmingcalendar.core.FarmingData.PatchDef;
import com.farmingcalendar.core.FarmingData.State;
import com.farmingcalendar.core.Planner;
import com.farmingcalendar.core.Planner.Candidate;
import com.farmingcalendar.core.Planner.Op;
import com.farmingcalendar.core.Planner.Sent;
import com.farmingcalendar.core.Planner.Settings;
import com.farmingcalendar.core.Planner.Toggle;
import com.farmingcalendar.core.Predictor;
import com.farmingcalendar.core.Predictor.Prediction;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.BeforeClass;
import org.junit.Test;

public class CoreTest
{
	private static FarmingData data;
	private static final long NOW = 1_800_000_000L;

	@BeforeClass
	public static void load() throws IOException
	{
		data = FarmingData.load(new com.google.gson.Gson());
	}

	/** Finds a stored varbit value for a patch type that decodes to the wanted crop, state and stage. */
	private static int value(String impl, String produce, Crop crop, int stage)
	{
		for (int v = 0; v <= 255; v++)
		{
			State s = data.decode(impl, v);
			if (s != null && s.produce.equals(produce) && s.crop == crop && (stage < 0 || s.stage == stage))
			{
				return v;
			}
		}
		throw new AssertionError("no value for " + impl + " " + produce + " " + crop + " " + stage);
	}

	private static PatchDef patch(String region, String impl)
	{
		for (PatchDef p : data.patches())
		{
			if (p.region.equals(region) && p.impl.equals(impl))
			{
				return p;
			}
		}
		throw new AssertionError(region + " " + impl);
	}

	private static Prediction predict(PatchDef p, int value, long seen, Integer precision, Integer offset)
	{
		return Predictor.predict(data, p, value + ":" + seen, precision, offset, false);
	}

	@Test
	public void dataIsComplete()
	{
		assertTrue(data.patches().size() > 100);
		assertNotNull(data.patch("4922.7905"));
		assertEquals(4922, data.guildRegionId());
		for (PatchDef p : data.patches())
		{
			assertNotNull(p.impl, data.impl(p.impl));
		}
	}

	@Test
	public void yewDoneTimeIsTenTicksOfFortyMinutes()
	{
		PatchDef varrock = patch("Varrock", "TREE");
		int v = value("TREE", "YEW", Crop.GROWING, 0);
		// tick offset known to be 7 minutes at 640 minute precision
		Prediction p = predict(varrock, v, NOW, 640, 7);
		assertTrue(p.precise);
		assertEquals(Crop.GROWING, p.crop);
		// ticks fall on (t + 7 min) % 40 min == 0, so done + 7 min is a multiple of 40 minutes
		assertEquals(0, (p.done + 7 * 60) % (40 * 60));
		assertTrue(p.done > NOW + 9 * 40 * 60 - 1);
		assertTrue(p.done <= NOW + 10 * 40 * 60 + 1);
	}

	@Test
	public void unknownOffsetIsFlaggedApproximate()
	{
		Prediction p = predict(patch("Varrock", "TREE"), value("TREE", "YEW", Crop.GROWING, 0), NOW, null, null);
		assertFalse(p.precise);
	}

	@Test
	public void checkHealthStageFinishesAtOnce()
	{
		PatchDef varrock = patch("Varrock", "TREE");
		int v = value("TREE", "OAK", Crop.GROWING, data.produce("OAK").stages - 1);
		Prediction p = predict(varrock, v, NOW, 640, 0);
		assertTrue(p.done <= NOW);
	}

	@Test
	public void garbageIsIgnored()
	{
		PatchDef varrock = patch("Varrock", "TREE");
		assertNull(Predictor.predict(data, varrock, "nonsense", null, null, false));
		assertNull(Predictor.predict(data, varrock, null, null, null, false));
		assertNull(Predictor.predict(data, varrock, "12:0", null, null, false));
	}

	// ---- planner

	private static Candidate candidate(PatchDef p, Prediction pred)
	{
		Candidate c = new Candidate();
		c.id = p.key();
		c.where = p.where();
		c.kind = "Tree";
		c.p = pred;
		c.healthCheck = data.impl(p.impl).healthCheck;
		return c;
	}

	private static Map<String, Toggle> on(String id, boolean notify)
	{
		Map<String, Toggle> m = new HashMap<>();
		m.put(id, new Toggle(true, notify));
		return m;
	}

	private static Prediction growing(PatchDef p, String crop)
	{
		return predict(p, value(p.impl, crop, Crop.GROWING, 0), NOW, 640, 0);
	}

	@Test
	public void growingTreeMakesOneEntryWithOnTimeReminder()
	{
		PatchDef varrock = patch("Varrock", "TREE");
		Candidate c = candidate(varrock, growing(varrock, "YEW"));
		List<Op> ops = Planner.plan(Collections.singletonList(c), on(c.id, true), new HashMap<>(), new Settings(), NOW, false);
		assertEquals(1, ops.size());
		Op op = ops.get(0);
		assertEquals("upsert", op.op);
		assertEquals(c.p.done, op.start);
		assertEquals(1, op.reminders.size());
		assertEquals(0, op.reminders.get(0).minutes);
		assertEquals("popup", op.reminders.get(0).method);
		assertTrue(op.title, op.title.startsWith("Yew ready"));
	}

	@Test
	public void notifyOffMeansNoReminders()
	{
		PatchDef varrock = patch("Varrock", "TREE");
		Candidate c = candidate(varrock, growing(varrock, "YEW"));
		List<Op> ops = Planner.plan(Collections.singletonList(c), on(c.id, false), new HashMap<>(), new Settings(), NOW, false);
		assertEquals(0, ops.get(0).reminders.size());
	}

	@Test
	public void unchangedEntryIsNotResentButResyncResendsIt()
	{
		PatchDef varrock = patch("Varrock", "TREE");
		Candidate c = candidate(varrock, growing(varrock, "YEW"));
		Settings s = new Settings();
		Op first = Planner.plan(Collections.singletonList(c), on(c.id, true), new HashMap<>(), s, NOW, false).get(0);
		Map<String, Sent> sent = new HashMap<>();
		sent.put(c.id, new Sent(first.fp, first.ready));
		assertTrue(Planner.plan(Collections.singletonList(c), on(c.id, true), sent, s, NOW + 60, false).isEmpty());
		assertEquals(1, Planner.plan(Collections.singletonList(c), on(c.id, true), sent, s, NOW + 60, true).size());
		// toggling the notification changes the entry
		assertEquals(1, Planner.plan(Collections.singletonList(c), on(c.id, false), sent, s, NOW + 60, false).size());
	}

	@Test
	public void replantedPatchReplacesTheSameEntry()
	{
		PatchDef varrock = patch("Varrock", "TREE");
		Settings s = new Settings();
		Candidate yew = candidate(varrock, growing(varrock, "YEW"));
		Op first = Planner.plan(Collections.singletonList(yew), on(yew.id, true), new HashMap<>(), s, NOW, false).get(0);
		Map<String, Sent> sent = new HashMap<>();
		sent.put(yew.id, new Sent(first.fp, first.ready));
		Candidate magic = candidate(varrock, growing(varrock, "MAGIC"));
		List<Op> ops = Planner.plan(Collections.singletonList(magic), on(magic.id, true), sent, s, NOW + 600, false);
		assertEquals(1, ops.size());
		assertEquals(yew.id, ops.get(0).id);
		assertTrue(ops.get(0).title.startsWith("Magic"));
	}

	@Test
	public void grownTreeStaysUntilCheckedThenGoes()
	{
		PatchDef varrock = patch("Varrock", "TREE");
		Settings s = new Settings();
		Candidate growing = candidate(varrock, growing(varrock, "OAK"));
		Op first = Planner.plan(Collections.singletonList(growing), on(growing.id, true), new HashMap<>(), s, NOW, false).get(0);
		Map<String, Sent> sent = new HashMap<>();
		sent.put(growing.id, new Sent(first.fp, first.ready));

		// long after it finished, still unchecked (stored as the check-health stage): entry stays, nothing to send
		long later = first.ready + 3 * 3600;
		int unchecked = value("TREE", "OAK", Crop.GROWING, data.produce("OAK").stages - 1);
		Candidate waiting = candidate(varrock, predict(varrock, unchecked, later - 600, 640, 0));
		assertTrue(Planner.plan(Collections.singletonList(waiting), on(waiting.id, true), sent, s, later, false).isEmpty());

		// the player checked its health: it becomes harvestable, and the entry goes
		int checked = value("TREE", "OAK", Crop.HARVESTABLE, -1);
		Candidate done = candidate(varrock, predict(varrock, checked, later, 640, 0));
		List<Op> ops = Planner.plan(Collections.singletonList(done), on(done.id, true), sent, s, later, false);
		assertEquals(1, ops.size());
		assertTrue(ops.get(0).isDelete());

		// with auto clear off it is left alone
		Settings keep = new Settings();
		keep.autoClear = Planner.AutoClear.NEVER;
		assertTrue(Planner.plan(Collections.singletonList(done), on(done.id, true), sent, keep, later, false).isEmpty());
	}

	@Test
	public void clearedAndDeadPatchesDropTheirEntry()
	{
		PatchDef varrock = patch("Varrock", "TREE");
		Settings s = new Settings();
		Candidate growing = candidate(varrock, growing(varrock, "OAK"));
		Op first = Planner.plan(Collections.singletonList(growing), on(growing.id, true), new HashMap<>(), s, NOW, false).get(0);
		Map<String, Sent> sent = new HashMap<>();
		sent.put(growing.id, new Sent(first.fp, first.ready));
		for (int v : new int[] {value("TREE", "WEEDS", Crop.GROWING, 3), value("TREE", "OAK", Crop.DEAD, -1)})
		{
			Candidate c = candidate(varrock, predict(varrock, v, NOW + 100, 640, 0));
			List<Op> ops = Planner.plan(Collections.singletonList(c), on(c.id, true), sent, s, NOW + 100, false);
			assertEquals(1, ops.size());
			assertTrue(ops.get(0).isDelete());
		}
	}

	@Test
	public void alreadyFinishedPatchGetsNoNewEntry()
	{
		PatchDef varrock = patch("Varrock", "TREE");
		Prediction p = predict(varrock, value("TREE", "OAK", Crop.GROWING, 0), NOW - 100 * 3600, 640, 0);
		assertTrue(p.done < NOW);
		Candidate c = candidate(varrock, p);
		assertTrue(Planner.plan(Collections.singletonList(c), on(c.id, true), new HashMap<>(), new Settings(), NOW, false).isEmpty());
	}

	@Test
	public void switchingAPatchOffDeletesItsEntry()
	{
		Map<String, Sent> sent = new HashMap<>();
		sent.put("12854.4771", new Sent("x", NOW));
		List<Op> ops = Planner.plan(new ArrayList<>(), new HashMap<>(), sent, new Settings(), NOW, false);
		assertEquals(1, ops.size());
		assertTrue(ops.get(0).isDelete());
		assertEquals("12854.4771", ops.get(0).id);
	}

	@Test
	public void contractSlotSuppressesItsCarrierPatch()
	{
		PatchDef guildHerb = null;
		for (PatchDef p : data.patches())
		{
			if (p.regionId == data.guildRegionId() && p.impl.equals("HERB"))
			{
				guildHerb = p;
			}
		}
		Prediction p = predict(guildHerb, value("HERB", "RANARR", Crop.GROWING, 0), NOW, 640, 0);
		Candidate patch = candidate(guildHerb, p);
		Candidate contract = candidate(guildHerb, p);
		contract.id = "contract";
		contract.contract = true;
		contract.contractName = "Ranarr";
		contract.carrier = guildHerb.key();
		Map<String, Toggle> toggles = on(patch.id, true);
		toggles.put("contract", new Toggle(true, true));
		List<Candidate> both = new ArrayList<>();
		both.add(patch);
		both.add(contract);
		List<Op> ops = Planner.plan(both, toggles, new HashMap<>(), new Settings(), NOW, false);
		assertEquals(1, ops.size());
		assertEquals("contract", ops.get(0).id);
		assertTrue(ops.get(0).title, ops.get(0).title.startsWith("Farming contract: Ranarr ready"));
	}

	@Test
	public void fruitTreeRegrowthCanBeSwitchedOff()
	{
		PatchDef p = patch("Catherby", "FRUIT_TREE");
		int v = value("FRUIT_TREE", "APPLE", Crop.HARVESTABLE, 0);
		Candidate c = candidate(p, predict(p, v, NOW, 640, 0));
		assertTrue(c.p.done > NOW);
		Settings s = new Settings();
		assertEquals(1, Planner.plan(Collections.singletonList(c), on(c.id, true), new HashMap<>(), s, NOW, false).size());
		assertTrue(Planner.plan(Collections.singletonList(c), on(c.id, true), new HashMap<>(), s, NOW, false).get(0).title.contains("regrown"));
		s.remindRegrow = false;
		assertTrue(Planner.plan(Collections.singletonList(c), on(c.id, true), new HashMap<>(), s, NOW, false).isEmpty());
	}
}
