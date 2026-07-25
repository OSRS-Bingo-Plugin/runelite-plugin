package com.osrsbingo.api;

import java.util.List;
import lombok.Value;

@Value
public class BoardTile
{
	String id;
	int position;
	String kind;
	String title;
	String description;
	int points;
	Progress progress;
	boolean pending;
	List<Integer> itemIds;
	List<Integer> obtainedItemIds;
	String countMode;

	@Value
	public static class Progress
	{
		boolean completed;
		int current;
		int target;
	}
}
