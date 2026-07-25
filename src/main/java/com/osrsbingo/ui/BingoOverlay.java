package com.osrsbingo.ui;

import com.osrsbingo.api.BoardResponse;
import com.osrsbingo.api.BoardTile;
import com.osrsbingo.board.BoardState;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.ToIntFunction;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The bingo board drawn over the viewport: a {@code size × size} grid of named,
 * tinted cells at their true board positions. Reads {@link BoardState} and never
 * fetches anything itself.
 *
 * <p>This extends {@link Overlay} directly rather than {@link
 * net.runelite.client.ui.overlay.OverlayPanel}. RuneLite's {@code
 * OverlayRenderer} translates the {@link Graphics2D} origin to the overlay's
 * current on-screen location, then calls {@link #render(Graphics2D)}, and only
 * afterwards resizes {@code getBounds()} to whatever {@link Dimension} this
 * method returns. So inside {@code render} the graphics origin (0, 0) already
 * <em>is</em> the overlay's top-left corner: everything is painted relative to
 * local (0, 0) and an accurate {@link Dimension} is handed back so RuneLite (not
 * this class) owns bounds, dragging and snapping. {@code getBounds()} is never
 * read for positioning here.
 *
 * <p>The grid-math and text-wrap logic is factored into pure package-private
 * static helpers ({@link #byPosition}, {@link #rowOf}, {@link #colOf}, {@link
 * #cellColor}, {@link #wrapLabel}) so it is unit-tested without a live
 * {@link Graphics2D}. The per-frame allocations (a tile array and a couple of
 * short line lists) are the routine cost of a hand-painted overlay.
 */
public class BingoOverlay extends Overlay
{
	private static final int CELL_W = 70;
	private static final int CELL_H = 48;
	private static final int GAP = 2;
	private static final int PADDING = 8;
	private static final int TEXT_PAD = 4;
	private static final int MAX_TEXT_LINES = 2;

	private static final Color BACKGROUND = new Color(0, 0, 0, 150);
	private static final Color CELL_BORDER = Color.DARK_GRAY;
	private static final Color EMPTY_CELL = new Color(45, 45, 45);
	private static final Color TEXT_COLOR = Color.WHITE;
	private static final Color COMPLETED = new Color(60, 160, 60);
	private static final Color PENDING = new Color(190, 160, 50);
	private static final Color OUTSTANDING = new Color(70, 70, 70);

	private final BoardState boardState;
	private final BooleanSupplier visible;

	// NOT @Inject: BoardState has no Guice binding; BingoPlugin builds this.
	public BingoOverlay(BoardState boardState, BooleanSupplier visible)
	{
		this.boardState = boardState;
		this.visible = visible;
		setPosition(OverlayPosition.TOP_LEFT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!visible.getAsBoolean())
		{
			return null;
		}
		BoardResponse board = boardState.getBoard();
		if (board == null)
		{
			return null;
		}
		int size = board.getEvent().getSize();
		if (size <= 0)
		{
			return null;
		}

		BoardTile[] byPos = byPosition(board.getTiles(), size);
		FontMetrics fm = graphics.getFontMetrics();
		int lineHeight = fm.getHeight();

		int gridWidth = size * CELL_W + (size - 1) * GAP;
		int gridHeight = size * CELL_H + (size - 1) * GAP;
		int width = gridWidth + PADDING * 2;
		int height = gridHeight + PADDING * 2;

		graphics.setColor(BACKGROUND);
		graphics.fillRect(0, 0, width, height);

		for (int position = 0; position < size * size; position++)
		{
			int x = PADDING + colOf(position, size) * (CELL_W + GAP);
			int y = PADDING + rowOf(position, size) * (CELL_H + GAP);
			BoardTile tile = byPos[position];

			if (tile == null)
			{
				graphics.setColor(EMPTY_CELL);
				graphics.drawRect(x, y, CELL_W, CELL_H);
				continue;
			}

			graphics.setColor(cellColor(tile));
			graphics.fillRect(x, y, CELL_W, CELL_H);
			graphics.setColor(CELL_BORDER);
			graphics.drawRect(x, y, CELL_W, CELL_H);

			List<String> lines = wrapLabel(tile.getTitle(), CELL_W - TEXT_PAD * 2, MAX_TEXT_LINES,
				fm::stringWidth);
			graphics.setColor(TEXT_COLOR);
			int textY = y + (CELL_H - lines.size() * lineHeight) / 2 + fm.getAscent();
			for (String line : lines)
			{
				int textX = x + (CELL_W - fm.stringWidth(line)) / 2;
				graphics.drawString(line, textX, textY);
				textY += lineHeight;
			}
		}

		return new Dimension(width, height);
	}

	/** Row of a position in a {@code size}-wide grid. */
	static int rowOf(int position, int size)
	{
		return position / size;
	}

	/** Column of a position in a {@code size}-wide grid. */
	static int colOf(int position, int size)
	{
		return position % size;
	}

	/**
	 * Tiles indexed by board position: array length {@code size*size}, {@code
	 * null} where no tile sits. Positions outside the grid are ignored (never
	 * throw). MANUAL tiles occupy their cell like any other — they have a title
	 * and a completed state, just no items.
	 */
	static BoardTile[] byPosition(List<BoardTile> tiles, int size)
	{
		BoardTile[] byPos = new BoardTile[size * size];
		if (tiles == null)
		{
			return byPos;
		}
		for (BoardTile tile : tiles)
		{
			int position = tile.getPosition();
			if (position >= 0 && position < byPos.length)
			{
				byPos[position] = tile;
			}
		}
		return byPos;
	}

	/** Green completed, else yellow pending, else grey outstanding. */
	static Color cellColor(BoardTile tile)
	{
		if (tile.getProgress().isCompleted())
		{
			return COMPLETED;
		}
		if (tile.isPending())
		{
			return PENDING;
		}
		return OUTSTANDING;
	}

	/**
	 * Greedy word-wrap of {@code text} into at most {@code maxLines} lines no
	 * wider than {@code maxWidth} (measured by {@code widthOf}). If words remain
	 * after the last line, or a single word is itself too wide, the last line is
	 * trimmed and ends with an ellipsis. {@code widthOf} is injected so the logic
	 * is testable without a live {@link Graphics2D}.
	 */
	static List<String> wrapLabel(String text, int maxWidth, int maxLines, ToIntFunction<String> widthOf)
	{
		List<String> lines = new ArrayList<>();
		if (text == null || text.trim().isEmpty())
		{
			return lines;
		}
		String[] words = text.trim().split("\\s+");
		int i = 0;
		while (i < words.length && lines.size() < maxLines)
		{
			StringBuilder line = new StringBuilder(words[i++]);
			while (i < words.length && widthOf.applyAsInt(line + " " + words[i]) <= maxWidth)
			{
				line.append(' ').append(words[i++]);
			}
			lines.add(line.toString());
		}

		boolean wordsRemain = i < words.length;
		int last = lines.size() - 1;
		if (last >= 0 && (wordsRemain || widthOf.applyAsInt(lines.get(last)) > maxWidth))
		{
			lines.set(last, ellipsize(lines.get(last), maxWidth, widthOf));
		}
		return lines;
	}

	/** Trims trailing characters until {@code result} fits {@code maxWidth}; always ends with an ellipsis. */
	private static String ellipsize(String s, int maxWidth, ToIntFunction<String> widthOf)
	{
		int len = s.length();
		while (len > 0 && widthOf.applyAsInt(s.substring(0, len) + "…") > maxWidth)
		{
			len--;
		}
		return s.substring(0, len) + "…";
	}
}
