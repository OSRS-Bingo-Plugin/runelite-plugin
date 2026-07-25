package com.osrsbingo;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(BingoConfig.GROUP)
public interface BingoConfig extends Config
{
	String GROUP = "osrsbingo";

	@ConfigSection(
		name = "Connection",
		description = "Which bingo board to talk to",
		position = 0
	)
	String connectionSection = "connection";

	@ConfigSection(
		name = "What to report",
		description = "Which kinds of loot are sent to the board",
		position = 1
	)
	String reportingSection = "reporting";

	@ConfigSection(
		name = "Proof",
		description = "Screenshot capture",
		position = 2
	)
	String proofSection = "proof";

	@ConfigSection(
		name = "Display & feedback",
		description = "In-client display options",
		position = 3
	)
	String displaySection = "display";

	@ConfigItem(
		keyName = "boardCode",
		name = "Board code",
		description = "Your team's board code from the bingo site. Treat it like a password.",
		section = connectionSection,
		position = 0,
		secret = true
	)
	default String boardCode()
	{
		return "";
	}

	@ConfigItem(
		keyName = "reportNpcLoot",
		name = "Boss & monster kills",
		description = "Report loot from NPC kills",
		section = reportingSection,
		position = 0
	)
	default boolean reportNpcLoot()
	{
		return true;
	}

	@ConfigItem(
		keyName = "reportEventLoot",
		name = "Raid & chest rewards",
		description = "Report loot from CoX, ToB, ToA, Barrows and other reward chests",
		section = reportingSection,
		position = 1
	)
	default boolean reportEventLoot()
	{
		return true;
	}

	@ConfigItem(
		keyName = "reportPickpocket",
		name = "Pickpocketing",
		description = "Report loot from pickpocketing. Only useful if your bingo has thieving tiles.",
		section = reportingSection,
		position = 2
	)
	default boolean reportPickpocket()
	{
		return false;
	}

	@ConfigItem(
		keyName = "attachScreenshots",
		name = "Attach screenshots",
		description = "Capture a screenshot of each reported drop as proof for the reviewer. "
			+ "Screenshots are sent to your bingo backend and saved locally under "
			+ "screenshots/osrs-bingo.",
		section = proofSection,
		position = 0
	)
	default boolean attachScreenshots()
	{
		return true;
	}

	@ConfigItem(
		keyName = "notifyInChat",
		name = "Chat messages",
		description = "Post a chat line when a drop is logged, or when something goes wrong",
		section = displaySection,
		position = 1
	)
	default boolean notifyInChat()
	{
		return true;
	}
}
