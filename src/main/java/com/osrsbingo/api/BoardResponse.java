package com.osrsbingo.api;

import java.util.List;
import lombok.Value;

@Value
public class BoardResponse
{
	Event event;
	Team team;
	List<BoardTile> tiles;
	List<LeaderboardEntry> leaderboard;

	@Value
	public static class Event
	{
		String id;
		String name;
		String status;
		int size;
	}

	@Value
	public static class Team
	{
		String id;
		String name;
		int score;
	}

	@Value
	public static class LeaderboardEntry
	{
		String teamId;
		String teamName;
		int score;
	}
}
