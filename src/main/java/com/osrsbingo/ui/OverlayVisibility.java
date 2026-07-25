package com.osrsbingo.ui;

import com.osrsbingo.BingoConfig;
import net.runelite.client.config.ConfigManager;

/**
 * Persists the board-overlay on/off state as a hidden config value under group
 * {@link BingoConfig#GROUP}, key {@code showOverlay}. It is deliberately NOT a
 * {@code @ConfigItem}, so it sticks across sessions without appearing in the
 * RuneLite Settings panel — the toggle lives in {@link BingoPanel} instead.
 */
public final class OverlayVisibility
{
	static final String KEY = "showOverlay";

	private OverlayVisibility()
	{
	}

	public static boolean isVisible(ConfigManager configManager)
	{
		return Boolean.parseBoolean(configManager.getConfiguration(BingoConfig.GROUP, KEY));
	}

	public static void set(ConfigManager configManager, boolean visible)
	{
		configManager.setConfiguration(BingoConfig.GROUP, KEY, Boolean.toString(visible));
	}
}
