package com.farmingcalendar.core;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The time tracker's patch table, crops and varbit decoding, as bundled in {@code farming-data.json}.
 * The file is produced at build time by {@code gradlew dumpFarming}, so the plugin needs nothing from
 * RuneLite's time tracking internals at runtime.
 */
public final class FarmingData
{
	/** What a patch is doing, mirroring RuneLite's CropState. */
	public enum Crop
	{
		HARVESTABLE, GROWING, DISEASED, DEAD, EMPTY, FILLING
	}

	public static final class ProduceDef
	{
		public String name;
		/** Value of the Farming Guild contract varbit for this crop, or -1. */
		public int contract;
		public String impl;
		public int item;
		/** Minutes per growth tick. */
		public int tick;
		public int stages;
		/** Minutes per regrowth tick, 0 if the crop does not regrow. */
		public int regrow;
		public int harvestStages;
	}

	public static final class Run
	{
		public int lo;
		public int hi;
		public String p;
		public String c;
		public int s;
	}

	public static final class ImplDef
	{
		public String tab;
		public boolean healthCheck;
		public List<Run> runs;
	}

	public static final class PatchDef
	{
		public String region;
		public int regionId;
		public boolean definite;
		public String name;
		public int varbit;
		public String impl;

		/** The time tracker's config key for this patch, and this plugin's id for it. */
		public String key()
		{
			return regionId + "." + varbit;
		}

		/** For example "Ardougne North", or "Varrock". */
		public String where()
		{
			return name == null || name.isEmpty() ? region : region + " " + name;
		}
	}

	/** One decoded varbit value. */
	public static final class State
	{
		public final String produce;
		public final Crop crop;
		public final int stage;

		State(String produce, Crop crop, int stage)
		{
			this.produce = produce;
			this.crop = crop;
			this.stage = stage;
		}
	}

	private Map<String, ProduceDef> produce;
	private Map<String, ImplDef> impl;
	private List<PatchDef> patches;
	private int guildRegionId;

	private transient Map<String, State[]> tables;
	private transient Map<String, PatchDef> byKey;
	private transient List<Map.Entry<String, ProduceDef>> produceList;

	public static FarmingData load(Gson gson) throws IOException
	{
		try (InputStream in = FarmingData.class.getResourceAsStream("/com/farmingcalendar/farming-data.json"))
		{
			if (in == null)
			{
				throw new IOException("farming-data.json is missing from the jar");
			}
			return parse(new InputStreamReader(in, StandardCharsets.UTF_8), gson);
		}
	}

	public static FarmingData parse(Reader reader, Gson gson)
	{
		FarmingData data = gson.fromJson(reader, FarmingData.class);
		data.index();
		return data;
	}

	private void index()
	{
		tables = new HashMap<>();
		for (Map.Entry<String, ImplDef> e : impl.entrySet())
		{
			State[] table = new State[256];
			for (Run r : e.getValue().runs)
			{
				Crop crop = Crop.valueOf(r.c);
				for (int v = r.lo; v <= r.hi; v++)
				{
					table[v] = new State(r.p, crop, r.s);
				}
			}
			tables.put(e.getKey(), table);
		}
		byKey = new LinkedHashMap<>();
		for (PatchDef p : patches)
		{
			byKey.put(p.key(), p);
		}
		produceList = new ArrayList<>(produce.entrySet());
	}

	public List<PatchDef> patches()
	{
		return patches;
	}

	public PatchDef patch(String key)
	{
		return byKey.get(key);
	}

	public int guildRegionId()
	{
		return guildRegionId;
	}

	public ProduceDef produce(String key)
	{
		return key == null ? null : produce.get(key);
	}

	/** First crop with this item id, which is how the time tracker stores the farming contract. */
	public String produceKeyByItem(int item)
	{
		for (Map.Entry<String, ProduceDef> e : produceList)
		{
			if (e.getValue().item == item)
			{
				return e.getKey();
			}
		}
		return null;
	}

	public ImplDef impl(String key)
	{
		return impl.get(key);
	}

	/** Decodes a stored varbit value for a patch type, or null if the value means nothing. */
	public State decode(String implKey, int value)
	{
		State[] table = tables.get(implKey);
		return table == null || value < 0 || value > 255 ? null : table[value];
	}
}
