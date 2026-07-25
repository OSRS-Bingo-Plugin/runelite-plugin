package com.osrsbingo.ui;

import com.osrsbingo.api.BoardResponse;
import com.osrsbingo.board.BoardState;
import com.osrsbingo.board.ConnectionState;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.api.ItemComposition;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * The sidebar panel: a connection status line, team summary, and the
 * points-descending list of tiles the team still needs.
 */
public class BingoPanel extends PluginPanel
{
	private static final String UNKNOWN_ITEM_NAME = "unknown item";

	private final BoardState boardState;
	private final ItemManager itemManager;
	private final ClientThread clientThread;
	private final ConfigManager configManager;

	/**
	 * Item id -> resolved name. Names never change, so an id is resolved at
	 * most once. Written on the client thread (see {@link #resolveMissingNames}),
	 * read on the EDT (see {@link #detailLine}) — hence the concurrent map.
	 */
	private final Map<Integer, String> itemNameCache = new ConcurrentHashMap<>();

	private final JLabel eventLabel = new JLabel();
	private final JLabel statusLabel = new JLabel();
	private final JLabel teamLabel = new JLabel();
	private final JLabel progressLabel = new JLabel();
	private final JPanel rowsPanel = new JPanel();
	private final JButton overlayToggle = new JButton();

	public BingoPanel(BoardState boardState, ItemManager itemManager, ClientThread clientThread,
		ConfigManager configManager)
	{
		this.boardState = boardState;
		this.itemManager = itemManager;
		this.clientThread = clientThread;
		this.configManager = configManager;

		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		add(buildHeader(), BorderLayout.NORTH);

		rowsPanel.setLayout(new BoxLayout(rowsPanel, BoxLayout.Y_AXIS));
		rowsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(rowsPanel, BorderLayout.CENTER);

		refresh();
	}

	private JPanel buildHeader()
	{
		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel titleRow = new JPanel(new BorderLayout());
		titleRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JLabel title = new JLabel("OSRS Bingo");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		titleRow.add(title, BorderLayout.WEST);

		JButton refreshButton = new JButton("Refresh");
		refreshButton.setFocusable(false);
		refreshButton.addActionListener(e -> boardState.requestRefresh());
		titleRow.add(refreshButton, BorderLayout.EAST);
		header.add(titleRow);

		overlayToggle.setFocusable(false);
		overlayToggle.setText(overlayToggleText());
		overlayToggle.addActionListener(e ->
		{
			OverlayVisibility.set(configManager, !OverlayVisibility.isVisible(configManager));
			overlayToggle.setText(overlayToggleText());
		});
		JPanel overlayRow = new JPanel(new BorderLayout());
		overlayRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		overlayRow.add(overlayToggle, BorderLayout.WEST);
		header.add(overlayRow);

		eventLabel.setFont(FontManager.getRunescapeSmallFont());
		eventLabel.setForeground(Color.LIGHT_GRAY);
		header.add(eventLabel);

		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		header.add(statusLabel);

		header.add(javax.swing.Box.createVerticalStrut(6));

		teamLabel.setFont(FontManager.getRunescapeBoldFont());
		teamLabel.setForeground(Color.WHITE);
		header.add(teamLabel);

		progressLabel.setFont(FontManager.getRunescapeSmallFont());
		progressLabel.setForeground(Color.LIGHT_GRAY);
		header.add(progressLabel);

		header.add(javax.swing.Box.createVerticalStrut(6));
		return header;
	}

	private String overlayToggleText()
	{
		return "Board overlay: " + (OverlayVisibility.isVisible(configManager) ? "On" : "Off");
	}

	/** Safe to call from the poller thread; marshals onto the EDT. */
	public void refresh()
	{
		SwingUtilities.invokeLater(this::rebuild);
	}

	private void rebuild()
	{
		ConnectionState state = boardState.getState();
		statusLabel.setText(statusText(state));
		statusLabel.setForeground(statusColor(state));

		BoardResponse board = boardState.getBoard();
		rowsPanel.removeAll();

		if (board == null)
		{
			eventLabel.setText("");
			teamLabel.setText("");
			progressLabel.setText("");
			rowsPanel.revalidate();
			rowsPanel.repaint();
			return;
		}

		eventLabel.setText(board.getEvent().getName());
		teamLabel.setText(board.getTeam().getName() + " · " + board.getTeam().getScore() + " pts"
			+ rankSuffix(board));

		int completed = NeedsListModel.countCompleted(board);
		int total = NeedsListModel.countTotalItemTiles(board);
		progressLabel.setText(completed + "/" + total + " tiles · "
			+ boardState.getPendingCount() + " pending");

		if (total == 0)
		{
			rowsPanel.add(hint("This board has no item tiles — drops can't be auto-logged."));
			rowsPanel.revalidate();
			rowsPanel.repaint();
			return;
		}

		List<NeedsRow> needed = NeedsListModel.buildNeeded(board);
		List<NeedsRow> pending = NeedsListModel.buildPending(board);
		List<NeedsRow> done = NeedsListModel.buildCompleted(board);

		if (needed.isEmpty() && pending.isEmpty())
		{
			rowsPanel.add(hint("Every tile is done. Nice."));
		}
		else if (needed.isEmpty())
		{
			rowsPanel.add(hint("Nothing left to hunt — everything else is awaiting review."));
		}

		Set<Integer> missingIds = new HashSet<>();

		if (!needed.isEmpty())
		{
			JLabel heading = new JLabel("STILL NEEDED");
			heading.setFont(FontManager.getRunescapeSmallFont());
			heading.setForeground(Color.LIGHT_GRAY);
			rowsPanel.add(heading);
			rowsPanel.add(Box.createVerticalStrut(4));

			for (NeedsRow row : needed)
			{
				rowsPanel.add(renderRow(row, missingIds));
				rowsPanel.add(Box.createVerticalStrut(4));
			}
		}

		// Pending and completed collapse so the panel stays about what to hunt.
		// Pending starts open (it is the actionable one — a stuck submission is
		// worth noticing); completed starts closed.
		if (!pending.isEmpty())
		{
			rowsPanel.add(Box.createVerticalStrut(6));
			rowsPanel.add(collapsible("Pending", pending, true, missingIds));
		}
		if (!done.isEmpty())
		{
			rowsPanel.add(Box.createVerticalStrut(6));
			rowsPanel.add(collapsible("Completed", done, false, missingIds));
		}

		rowsPanel.revalidate();
		rowsPanel.repaint();

		if (!missingIds.isEmpty())
		{
			resolveMissingNames(missingIds);
		}
	}

	/**
	 * Resolves item names on the client thread — ItemManager.getItemComposition
	 * is a bare passthrough to Client state, which is unsafe to touch off the
	 * client thread. Only ids that are actually new are added to the cache, and
	 * a follow-up refresh() is scheduled only if at least one new name was
	 * cached — otherwise a stale/never-resolvable id would trigger refresh()
	 * forever, since it would remain "missing" on every subsequent rebuild().
	 */
	private void resolveMissingNames(Set<Integer> ids)
	{
		clientThread.invoke(() ->
		{
			boolean resolvedAny = false;
			for (int id : ids)
			{
				if (itemNameCache.containsKey(id))
				{
					continue;
				}
				String name;
				try
				{
					ItemComposition composition = itemManager.getItemComposition(id);
					name = composition == null ? null : composition.getName();
				}
				catch (RuntimeException e)
				{
					name = null;
				}
				itemNameCache.put(id, name == null || name.isEmpty() ? UNKNOWN_ITEM_NAME : name);
				resolvedAny = true;
			}
			if (resolvedAny)
			{
				refresh();
			}
		});
	}

	/**
	 * A click-to-toggle section. Swing has no built-in disclosure widget, so
	 * this is a header label that flips the visibility of a content panel.
	 */
	private JPanel collapsible(String title, List<NeedsRow> rows, boolean startExpanded, Set<Integer> missingIds)
	{
		JPanel container = new JPanel();
		container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
		container.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		content.setVisible(startExpanded);

		JLabel header = new JLabel(headerText(title, rows.size(), startExpanded));
		header.setFont(FontManager.getRunescapeSmallFont());
		header.setForeground(Color.LIGHT_GRAY);
		header.setCursor(new Cursor(Cursor.HAND_CURSOR));
		header.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				content.setVisible(!content.isVisible());
				header.setText(headerText(title, rows.size(), content.isVisible()));
				container.revalidate();
				container.repaint();
			}
		});

		for (NeedsRow row : rows)
		{
			content.add(renderRow(row, missingIds));
			content.add(Box.createVerticalStrut(4));
		}

		container.add(header);
		container.add(content);
		return container;
	}

	private static String headerText(String title, int count, boolean expanded)
	{
		return (expanded ? "▾ " : "▸ ") + title + " (" + count + ")";
	}

	private String rankSuffix(BoardResponse board)
	{
		if (board.getLeaderboard() == null || board.getLeaderboard().isEmpty())
		{
			return "";
		}
		int rank = 1;
		for (BoardResponse.LeaderboardEntry entry : board.getLeaderboard())
		{
			if (entry.getTeamId().equals(board.getTeam().getId()))
			{
				return " · " + ordinal(rank) + " of " + board.getLeaderboard().size();
			}
			rank++;
		}
		return "";
	}

	private static String ordinal(int n)
	{
		if (n % 100 >= 11 && n % 100 <= 13)
		{
			return n + "th";
		}
		switch (n % 10)
		{
			case 1:
				return n + "st";
			case 2:
				return n + "nd";
			case 3:
				return n + "rd";
			default:
				return n + "th";
		}
	}

	private JLabel hint(String text)
	{
		JLabel label = new JLabel("<html><body style='width:150px'>" + text + "</body></html>");
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(Color.LIGHT_GRAY);
		return label;
	}

	private JPanel renderRow(NeedsRow row, Set<Integer> missingIds)
	{
		JPanel panel = new JPanel(new BorderLayout(6, 0));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

		if (row.getIconItemId() > 0)
		{
			JLabel icon = new JLabel();
			icon.setPreferredSize(new Dimension(32, 32));
			AsyncBufferedImage image = itemManager.getImage(row.getIconItemId());
			image.addTo(icon);
			panel.add(icon, BorderLayout.WEST);
		}

		JPanel text = new JPanel(new GridLayout(0, 1));
		text.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JLabel title = new JLabel(row.getTitle());
		title.setFont(FontManager.getRunescapeSmallFont());
		title.setForeground(row.isPending() ? ColorScheme.PROGRESS_INPROGRESS_COLOR : Color.WHITE);
		text.add(title);

		JLabel meta = new JLabel(row.getCurrent() + "/" + row.getTarget()
			+ " · " + row.getPoints() + " pts" + (row.isPending() ? " · pending" : ""));
		meta.setFont(FontManager.getRunescapeSmallFont());
		meta.setForeground(Color.LIGHT_GRAY);
		text.add(meta);

		String detail = detailLine(row, missingIds);
		if (detail != null)
		{
			JLabel detailLabel = new JLabel("<html><body style='width:120px'>" + detail + "</body></html>");
			detailLabel.setFont(FontManager.getRunescapeSmallFont());
			detailLabel.setForeground(Color.GRAY);
			text.add(detailLabel);
		}

		panel.add(text, BorderLayout.CENTER);
		return panel;
	}

	/**
	 * COMPLETE_SET tiles list the specific items still missing; ANY tiles list
	 * what would count, since nothing can be individually missing.
	 */
	private String detailLine(NeedsRow row, Set<Integer> missingIds)
	{
		List<Integer> ids = row.isCompleteSet() ? row.getMissingItemIds() : row.getAcceptedItemIds();
		if (ids.isEmpty())
		{
			return null;
		}
		String prefix = row.isCompleteSet() ? "missing: " : "any of: ";
		StringBuilder sb = new StringBuilder(prefix);
		int shown = Math.min(ids.size(), 3);
		for (int i = 0; i < shown; i++)
		{
			if (i > 0)
			{
				sb.append(", ");
			}
			sb.append(itemName(ids.get(i), missingIds));
		}
		if (ids.size() > shown)
		{
			sb.append(", +").append(ids.size() - shown).append(" more");
		}
		return sb.toString();
	}

	/**
	 * Never touches ItemManager/Client — reads only the cache, which is
	 * populated on the client thread by {@link #resolveMissingNames}. Ids not
	 * yet cached get a harmless placeholder and are recorded as missing so the
	 * caller can schedule resolution.
	 */
	private String itemName(int itemId, Set<Integer> missingIds)
	{
		String cached = itemNameCache.get(itemId);
		if (cached != null)
		{
			return cached;
		}
		missingIds.add(itemId);
		return "…";
	}

	private static String statusText(ConnectionState state)
	{
		switch (state)
		{
			case NO_CODE:
				return "Paste your board code in settings";
			case LOADING:
				return "Connecting…";
			case READY:
				return "● Connected";
			case OFFLINE_CACHED:
				return "⚠ Reconnecting — drops are queued";
			case NOT_STARTED:
				return "This bingo hasn't started yet";
			case ARCHIVED:
				return "This bingo has ended";
			case INVALID_CODE:
				return "Board code not recognized";
			case UNREACHABLE:
			default:
				return "Can't reach the bingo server";
		}
	}

	private static Color statusColor(ConnectionState state)
	{
		if (state == ConnectionState.READY)
		{
			return ColorScheme.PROGRESS_COMPLETE_COLOR;
		}
		if (state == ConnectionState.OFFLINE_CACHED || state == ConnectionState.LOADING)
		{
			return ColorScheme.PROGRESS_INPROGRESS_COLOR;
		}
		return ColorScheme.PROGRESS_ERROR_COLOR;
	}
}
