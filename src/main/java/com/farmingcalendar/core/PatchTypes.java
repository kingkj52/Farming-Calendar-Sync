package com.farmingcalendar.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Friendly names for the time tracker's patch types, and the order the side panel lists them in. */
public final class PatchTypes
{
	private PatchTypes()
	{
	}

	/** Patch types in panel order: the long timers people plan around first. */
	public static final List<String> ORDER = Collections.unmodifiableList(Arrays.asList(
		"TREE", "FRUIT_TREE", "HARDWOOD_TREE", "CALQUAT", "HESPORI", "SPIRIT_TREE", "REDWOOD", "CELASTRUS",
		"CRYSTAL_TREE", "ANIMA", "HERB", "ALLOTMENT", "FLOWER", "BUSH", "HOPS", "CACTUS", "BELLADONNA",
		"MUSHROOM", "SEAWEED", "CORAL", "GRAPES", "COMPOST", "BIG_COMPOST"));

	private static final Map<String, String> NAMES = new TreeMap<>();

	static
	{
		NAMES.put("TREE", "Tree");
		NAMES.put("FRUIT_TREE", "Fruit tree");
		NAMES.put("HARDWOOD_TREE", "Hardwood tree");
		NAMES.put("CALQUAT", "Calquat");
		NAMES.put("HESPORI", "Hespori");
		NAMES.put("SPIRIT_TREE", "Spirit tree");
		NAMES.put("REDWOOD", "Redwood");
		NAMES.put("CELASTRUS", "Celastrus");
		NAMES.put("CRYSTAL_TREE", "Crystal tree");
		NAMES.put("ANIMA", "Anima");
		NAMES.put("HERB", "Herb");
		NAMES.put("ALLOTMENT", "Allotment");
		NAMES.put("FLOWER", "Flower");
		NAMES.put("BUSH", "Bush");
		NAMES.put("HOPS", "Hops");
		NAMES.put("CACTUS", "Cactus");
		NAMES.put("BELLADONNA", "Belladonna");
		NAMES.put("MUSHROOM", "Mushroom");
		NAMES.put("SEAWEED", "Seaweed");
		NAMES.put("CORAL", "Coral");
		NAMES.put("GRAPES", "Grapes");
		NAMES.put("COMPOST", "Compost bin");
		NAMES.put("BIG_COMPOST", "Big compost bin");
	}

	public static String name(String impl)
	{
		String n = NAMES.get(impl);
		return n != null ? n : impl.charAt(0) + impl.substring(1).toLowerCase().replace('_', ' ');
	}

	/** The patch types the user mostly cares about; used by the "my usual" quick select. */
	public static boolean isUsual(String impl)
	{
		switch (impl)
		{
			case "TREE":
			case "FRUIT_TREE":
			case "HARDWOOD_TREE":
			case "CALQUAT":
			case "HESPORI":
				return true;
			default:
				return false;
		}
	}
}
