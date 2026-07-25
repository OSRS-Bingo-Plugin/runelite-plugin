package com.osrsbingo.ui;

import com.osrsbingo.api.BoardResponse;
import com.osrsbingo.board.BoardState;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * A small always-available box showing the team name and the local clock. A
 * SEPARATE overlay from {@link BingoOverlay}, independently draggable, and shown
 * whenever a board is loaded — independent of the grid toggle, so the team and
 * time are visible without the full grid on screen.
 *
 * <p>Same rendering discipline as {@link BingoOverlay}: paint at local (0, 0),
 * return a {@link Dimension}, no I/O — reads {@link BoardState} and the system
 * clock only. The formatted clock string is rebuilt each frame (cheap).
 */
public class BingoInfoOverlay extends Overlay
{
	private static final int PADDING = 6;
	private static final int LINE_GAP = 2;
	private static final Color BACKGROUND = new Color(0, 0, 0, 150);
	private static final Color TEAM_COLOR = Color.WHITE;
	private static final Color CLOCK_COLOR = Color.LIGHT_GRAY;
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	private final BoardState boardState;

	// NOT @Inject: BoardState has no Guice binding; BingoPlugin builds this.
	public BingoInfoOverlay(BoardState boardState)
	{
		this.boardState = boardState;
		// A different default corner from BingoOverlay's TOP_LEFT so the two
		// overlays don't stack in the same snap corner on first launch; both
		// stay independently draggable.
		setPosition(OverlayPosition.TOP_CENTER);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		BoardResponse board = boardState.getBoard();
		if (board == null)
		{
			return null;
		}

		String team = board.getTeam().getName();
		String clock = formatClock(LocalDateTime.now());

		FontMetrics fm = graphics.getFontMetrics();
		int lineHeight = fm.getHeight();
		int contentWidth = Math.max(fm.stringWidth(team), fm.stringWidth(clock));
		int width = contentWidth + PADDING * 2;
		int height = lineHeight * 2 + LINE_GAP + PADDING * 2;

		graphics.setColor(BACKGROUND);
		graphics.fillRect(0, 0, width, height);

		int textY = PADDING + fm.getAscent();
		graphics.setColor(TEAM_COLOR);
		graphics.drawString(team, PADDING, textY);
		textY += lineHeight + LINE_GAP;
		graphics.setColor(CLOCK_COLOR);
		graphics.drawString(clock, PADDING, textY);

		return new Dimension(width, height);
	}

	/** Local date + time as {@code yyyy-MM-dd HH:mm} (24-hour). */
	static String formatClock(LocalDateTime now)
	{
		return CLOCK.format(now);
	}
}
