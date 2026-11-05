package com.farmingcalendar.core;

import com.farmingcalendar.core.FarmingData.Crop;
import com.farmingcalendar.core.FarmingData.PatchDef;
import com.farmingcalendar.core.FarmingData.ProduceDef;
import com.farmingcalendar.core.FarmingData.State;

/**
 * Works out when a patch finishes from what RuneLite's time tracker has stored for it
 * ("varbit value:unix time seen"). This is the same arithmetic the time tracker uses for its own countdowns,
 * including the farming tick offset it learns by watching a real growth tick.
 */
public final class Predictor
{
	private Predictor()
	{
	}

	public static final class Prediction
	{
		public final String produceKey;
		public final ProduceDef produce;
		public final Crop crop;
		/** Epoch seconds the patch finishes (or finishes regrowing). 0 when there is no countdown. */
		public final long done;
		public final int stage;
		public final int stages;
		/** False when the farming tick offset is not known well enough, so the time can be off by a tick. */
		public final boolean precise;
		/** Epoch seconds the time tracker last saw this patch. */
		public final long observed;

		Prediction(String produceKey, ProduceDef produce, Crop crop, long done, int stage, int stages, boolean precise,
			long observed)
		{
			this.produceKey = produceKey;
			this.produce = produce;
			this.crop = crop;
			this.done = done;
			this.stage = stage;
			this.stages = stages;
			this.precise = precise;
			this.observed = observed;
		}

		public boolean isWeeds()
		{
			return "WEEDS".equals(produceKey) || "SCARECROW".equals(produceKey);
		}
	}

	/**
	 * @param stored    the time tracker's value for the patch, or null
	 * @param precision the stored farmTickOffsetPrecision in minutes, or null if unknown
	 * @param offset    the stored farmTickOffset in minutes, or null if unknown
	 * @param leagues   true on seasonal worlds, where farming ticks are five times shorter
	 */
	public static Prediction predict(FarmingData data, PatchDef patch, String stored, Integer precision, Integer offset,
		boolean leagues)
	{
		if (stored == null)
		{
			return null;
		}
		String[] parts = stored.split(":");
		if (parts.length != 2)
		{
			return null;
		}
		int value;
		long unixTime;
		try
		{
			value = Integer.parseInt(parts[0]);
			unixTime = Long.parseLong(parts[1]);
		}
		catch (NumberFormatException e)
		{
			return null;
		}
		if (unixTime <= 0)
		{
			return null;
		}
		State state = data.decode(patch.impl, value);
		if (state == null)
		{
			return null;
		}
		ProduceDef produce = data.produce(state.produce);
		if (produce == null)
		{
			return null;
		}

		boolean harvestStages = state.crop == Crop.HARVESTABLE || state.crop == Crop.FILLING;
		int stages = harvestStages ? produce.harvestStages : produce.stages;
		int tickRate;
		switch (state.crop)
		{
			case HARVESTABLE:
				tickRate = produce.regrow;
				break;
			case GROWING:
				tickRate = produce.tick;
				break;
			default:
				tickRate = 0;
		}
		if (leagues)
		{
			tickRate = tickRate / 5;
		}

		long done = 0;
		boolean precise = true;
		if (tickRate > 0)
		{
			precise = offsetKnown(tickRate, precision, offset);
			long tickTime = tickTime(tickRate, 0, unixTime, precision, offset);
			done = tickTime(tickRate, Math.max(0, stages - 1 - state.stage), tickTime, precision, offset);
		}
		return new Prediction(state.produce, produce, state.crop, done, state.stage, stages, precise, unixTime);
	}

	static boolean offsetKnown(int tickRate, Integer precision, Integer offset)
	{
		return precision != null && offset != null && (precision >= tickRate || precision >= 40);
	}

	/** The time of the tick that is {@code ticks} ticks after the one containing {@code requested}. */
	static long tickTime(int tickRate, int ticks, long requested, Integer precision, Integer offset)
	{
		long calculatedOffset = 0L;
		if (offsetKnown(tickRate, precision, offset))
		{
			calculatedOffset = (offset % tickRate) * 60L;
		}
		long shifted = requested + calculatedOffset;
		long currentTick = shifted - (shifted % (tickRate * 60L));
		long goalTick = currentTick + ticks * tickRate * 60L;
		return goalTick - calculatedOffset;
	}
}
