package com.farmingcalendar;

import com.farmingcalendar.core.FarmingData;
import com.farmingcalendar.core.FarmingData.PatchDef;
import com.farmingcalendar.core.PatchTypes;
import com.farmingcalendar.core.Planner.AutoClear;
import com.farmingcalendar.core.Planner.Toggle;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

/**
 * The side panel: connection status and buttons, a collapsible settings block, quick selections, a search box,
 * and every patch Time Tracking knows about, grouped by type, each with two switches: put it on the calendar,
 * and alert me when it is ready.
 */
class FarmingCalendarPanel extends PluginPanel
{
	private static final String CONTRACT = "contract";
	private static final String GUIDE = "https://github.com/kingkj52/farming-calendar-sync/blob/main/SETUP.md";
	private static final String[] QUICK = {
		"Quick select...",
		"My usual (trees, hardwood...)",
		"Everything on calendar",
		"Alerts on for all on calendar",
		"Alerts off for all",
		"Nothing on calendar"
	};

	private final FarmingCalendarPlugin plugin;
	private final FarmingData data;
	private final Store store;

	private final JLabel status = new JLabel();
	private final Map<String, Row> rows = new LinkedHashMap<>();
	private final List<Group> groups = new ArrayList<>();
	private final JTextField search = new JTextField();
	private boolean updating;

	// settings widgets
	private final JTextField url = new JTextField();
	private final JPasswordField token = new JPasswordField();
	private final JTextField calendarName = new JTextField();
	private final JTextField reminders = new JTextField();
	private final JCheckBox popup = new JCheckBox(wrap("Phone / app alert"));
	private final JCheckBox email = new JCheckBox(wrap("Email"));
	private final JSpinner length = new JSpinner(new SpinnerNumberModel(15, 1, 1440, 5));
	private final JComboBox<String> autoClear = new JComboBox<>(new String[] {
		"Once checked or harvested", "Never"});
	private final JSpinner deleteAfter = new JSpinner(new SpinnerNumberModel(0, 0, 10000, 1));
	private final JCheckBox regrow = new JCheckBox(wrap("Alert when fruit regrows"));
	private final JCheckBox notifyDefault = new JCheckBox(wrap("New patches alert by default"));
	private final JCheckBox showAccount = new JCheckBox(wrap("Account name in titles"));
	private final JCheckBox enabled = new JCheckBox(wrap("Syncing on"));

	FarmingCalendarPanel(FarmingCalendarPlugin plugin, FarmingData data)
	{
		super();
		this.plugin = plugin;
		this.data = data;
		this.store = plugin.store();

		JLabel title = new JLabel("Farming Calendar Sync");
		title.setForeground(Color.WHITE);
		add(title);

		status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		add(status);

		JPanel buttons = new JPanel(new GridLayout(0, 1, 0, 3));
		buttons.setOpaque(false);
		buttons.add(button("Setup guide (opens GitHub)", "Step by step instructions and the script to copy.",
			() -> LinkBrowser.browse(GUIDE)));
		buttons.add(button("Test connection", "Checks the web app address and secret, and creates the calendar if needed.",
			plugin::testConnection));
		buttons.add(button("Send a test alert in 2 minutes", "Puts a test entry on the calendar to prove the phone alert works.",
			plugin::sendTestReminder));
		buttons.add(button("Resend everything", "Sends every switched-on patch again, even ones that look up to date.",
			plugin::resync));
		add(buttons);

		add(buildSettings());

		JComboBox<String> quick = new JComboBox<>(QUICK);
		quick.addActionListener(e ->
		{
			int i = quick.getSelectedIndex();
			if (i > 0)
			{
				quickSelect(i);
				quick.setSelectedIndex(0);
			}
		});
		add(quick);

		quick.setMinimumSize(new Dimension(0, 24));
		quick.setPrototypeDisplayValue("Alerts on for all on calendar");
		search.setToolTipText("Filter patches by place or type");
		search.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				applyFilter();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				applyFilter();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				applyFilter();
			}
		});
		JPanel searchRow = new JPanel(new BorderLayout(4, 0));
		searchRow.setOpaque(false);
		searchRow.add(small("Find:"), BorderLayout.WEST);
		searchRow.add(search, BorderLayout.CENTER);
		add(searchRow);

		add(columnHeader());

		// the contract slot first, then every patch type
		Row contractRow = new Row(CONTRACT, plugin.contractLabel(), "Farming contract");
		contractRow.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 0));
		contractRow.setOpaque(true);
		contractRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		rows.put(CONTRACT, contractRow);
		add(contractRow);

		Map<String, List<PatchDef>> byType = new LinkedHashMap<>();
		for (String impl : PatchTypes.ORDER)
		{
			byType.put(impl, new ArrayList<>());
		}
		for (PatchDef p : data.patches())
		{
			byType.computeIfAbsent(p.impl, k -> new ArrayList<>()).add(p);
		}
		for (Map.Entry<String, List<PatchDef>> e : byType.entrySet())
		{
			if (e.getValue().isEmpty())
			{
				continue;
			}
			e.getValue().sort(Comparator.comparing(PatchDef::where));
			Group g = new Group(PatchTypes.name(e.getKey()) + " (" + e.getValue().size() + ")", e.getKey(), false);
			for (PatchDef p : e.getValue())
			{
				g.addRow(new Row(p.key(), p.where(), PatchTypes.name(p.impl)));
			}
			add(g);
			groups.add(g);
		}

		loadSettings();
		refresh();
	}

	// ---- building blocks

	private static JButton button(String text, String tip, Runnable action)
	{
		JButton b = new JButton(text);
		b.setToolTipText(tip);
		b.setFocusable(false);
		b.addActionListener(e -> action.run());
		return b;
	}

	/** Wraps text to a fixed width so it can never run past the edge of the side panel. */
	private static String wrap(String text)
	{
		return "<html><div style='width:150px'>" + text + "</div></html>";
	}

	private static JLabel small(String text)
	{
		JLabel l = new JLabel(text);
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		return l;
	}

	private JComponent columnHeader()
	{
		JPanel p = new JPanel(new BorderLayout());
		p.setOpaque(false);
		p.add(small("Patch"), BorderLayout.WEST);
		JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
		right.setOpaque(false);
		JLabel cal = small("Calendar");
		JLabel bell = small("Alert");
		cal.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));
		right.add(cal);
		right.add(bell);
		p.add(right, BorderLayout.EAST);
		return p;
	}

	private JComponent buildSettings()
	{
		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setOpaque(false);
		body.setBorder(BorderFactory.createEmptyBorder(4, 6, 6, 6));

		JLabel warning = new JLabel("<html><div style='width:185px'>Data sent: the names and ready times of the patches "
			+ "you switch on, to the web app address below, which is your own Google script. Nothing else, to no one else, "
			+ "and nothing at all until an address is entered.</div></html>");
		warning.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		warning.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(warning);

		url.setToolTipText("The https://script.google.com/.../exec link from Deploy > Manage deployments");
		token.setToolTipText("The same secret you put in the script");
		calendarName.setToolTipText("The calendar the entries go on. It is created if it does not exist.");
		reminders.setToolTipText("Minutes before the patch is ready, separated by commas. 0 is exactly on time. Up to five.");
		length.setToolTipText("How long each calendar entry is, in minutes");
		deleteAfter.setToolTipText("Remove an entry this many hours after its patch finished, whatever happened to it. 0 keeps it.");

		addField(body, "Web app address", url);
		addField(body, "Secret", token);
		addField(body, "Calendar name", calendarName);
		addField(body, "Alert minutes before (0 = on time)", reminders);
		body.add(popup);
		body.add(email);
		addField(body, "Entry length (minutes)", length);
		autoClear.setMinimumSize(new Dimension(0, 24));
		autoClear.setPrototypeDisplayValue("Once checked or harvested");
		addField(body, "Remove entries", autoClear);
		addField(body, "Also remove after (hours)", deleteAfter);
		body.add(regrow);
		body.add(notifyDefault);
		body.add(showAccount);
		body.add(enabled);
		for (Component c : body.getComponents())
		{
			if (c instanceof JCheckBox)
			{
				((JCheckBox) c).setOpaque(false);
				((JCheckBox) c).setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				((JCheckBox) c).setAlignmentX(Component.LEFT_ALIGNMENT);
			}
		}
		JButton save = button("Save settings", "Applies the settings above", this::saveSettings);
		save.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(save);

		Group settings = new Group("Settings", null, false);
		settings.setBody(body);
		return settings;
	}

	private static void addField(JPanel body, String label, JComponent field)
	{
		JLabel l = small(wrap(label));
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		field.setAlignmentX(Component.LEFT_ALIGNMENT);
		field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		body.add(l);
		body.add(field);
	}

	private void loadSettings()
	{
		url.setText(store.url());
		token.setText(store.token());
		calendarName.setText(store.calendar());
		reminders.setText(store.get("reminders", "0"));
		popup.setSelected(store.getBool("popup", true));
		email.setSelected(store.getBool("email", false));
		length.setValue(Math.max(1, store.getInt("eventMinutes", 15)));
		autoClear.setSelectedIndex(AutoClear.NEVER.name().equals(store.get("autoClear", "WHEN_DONE")) ? 1 : 0);
		deleteAfter.setValue(Math.max(0, store.getInt("deleteAfterHours", 0)));
		regrow.setSelected(store.getBool("remindRegrow", true));
		notifyDefault.setSelected(store.getBool("notifyDefault", true));
		showAccount.setSelected(store.showAccount());
		enabled.setSelected(store.enabled());
	}

	private void saveSettings()
	{
		String oldCalendar = store.calendar();
		store.set("url", url.getText().trim());
		store.set("token", new String(token.getPassword()).trim());
		String cal = calendarName.getText().trim();
		store.set("calendar", cal.isEmpty() ? "OSRS Farming" : cal);
		int[] r = Store.parseReminders(reminders.getText());
		StringBuilder sb = new StringBuilder();
		for (int m : r)
		{
			sb.append(sb.length() == 0 ? "" : ", ").append(m);
		}
		store.set("reminders", sb.toString());
		reminders.setText(sb.toString());
		store.set("popup", Boolean.toString(popup.isSelected()));
		store.set("email", Boolean.toString(email.isSelected()));
		store.set("eventMinutes", spinnerValue(length));
		store.set("autoClear", autoClear.getSelectedIndex() == 1 ? AutoClear.NEVER.name() : AutoClear.WHEN_DONE.name());
		store.set("deleteAfterHours", spinnerValue(deleteAfter));
		store.set("remindRegrow", Boolean.toString(regrow.isSelected()));
		store.set("notifyDefault", Boolean.toString(notifyDefault.isSelected()));
		store.set("showAccount", Boolean.toString(showAccount.isSelected()));
		store.set("enabled", Boolean.toString(enabled.isSelected()));
		showStatus("Settings saved.");
		if (!oldCalendar.equals(store.calendar()))
		{
			plugin.resync();
		}
		else
		{
			plugin.evaluateSoon(false, 1);
		}
	}

	private static String spinnerValue(JSpinner s)
	{
		return Integer.toString(((Number) s.getValue()).intValue());
	}

	// ---- rows and groups

	private final class Row extends JPanel
	{
		final String id;
		final String text;
		final String kind;
		final JLabel name;
		final JCheckBox sync = new JCheckBox();
		final JCheckBox notify = new JCheckBox();

		Row(String id, String text, String kind)
		{
			super(new BorderLayout(2, 0));
			this.id = id;
			this.text = text;
			this.kind = kind;
			setOpaque(false);
			setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 0));
			name = new JLabel(text)
			{
				@Override
				public String getToolTipText()
				{
					return plugin.describe(id);
				}
			};
			name.setToolTipText("");
			name.setForeground(Color.WHITE);
			add(name, BorderLayout.CENTER);

			sync.setOpaque(false);
			notify.setOpaque(false);
			sync.setToolTipText("Put this patch on the calendar");
			notify.setToolTipText("Alert me when it is ready");
			JPanel boxes = new JPanel(new GridLayout(1, 2, 8, 0));
			boxes.setOpaque(false);
			boxes.add(sync);
			boxes.add(notify);
			add(boxes, BorderLayout.EAST);

			sync.addActionListener(e -> changed(true));
			notify.addActionListener(e -> changed(false));
		}

		void changed(boolean syncClicked)
		{
			if (updating)
			{
				return;
			}
			boolean s = sync.isSelected();
			boolean n = notify.isSelected();
			if (syncClicked)
			{
				n = s && store.getBool("notifyDefault", true);
			}
			Map<String, Toggle> change = new HashMap<>();
			change.put(id, new Toggle(s, s && n));
			plugin.setToggles(change);
			show(new Toggle(s, s && n));
			updateGroupHeaders();
		}

		void show(Toggle t)
		{
			boolean was = updating;
			updating = true;
			sync.setSelected(t.sync);
			notify.setSelected(t.sync && t.notify);
			notify.setEnabled(t.sync);
			updating = was;
		}

		boolean matches(String q)
		{
			return q.isEmpty() || text.toLowerCase(Locale.ENGLISH).contains(q) || kind.toLowerCase(Locale.ENGLISH).contains(q);
		}
	}

	private final class Group extends JPanel
	{
		final String impl;
		final JLabel title;
		final String baseTitle;
		final JPanel body = new JPanel();
		final JCheckBox allSync = new JCheckBox();
		final JCheckBox allNotify = new JCheckBox();
		final List<Row> members = new ArrayList<>();
		boolean open;

		Group(String titleText, String impl, boolean open)
		{
			super(new BorderLayout());
			this.impl = impl;
			this.baseTitle = titleText;
			this.open = open;
			setOpaque(false);
			setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR));

			JPanel header = new JPanel(new BorderLayout());
			header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			header.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 0));
			title = new JLabel();
			title.setForeground(Color.WHITE);
			title.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			title.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					setOpen(!Group.this.open);
				}
			});
			header.add(title, BorderLayout.CENTER);

			if (impl != null)
			{
				allSync.setOpaque(false);
				allNotify.setOpaque(false);
				allSync.setToolTipText("Put every patch of this type on the calendar");
				allNotify.setToolTipText("Alert for every patch of this type");
				JPanel boxes = new JPanel(new GridLayout(1, 2, 8, 0));
				boxes.setOpaque(false);
				boxes.add(allSync);
				boxes.add(allNotify);
				header.add(boxes, BorderLayout.EAST);
				allSync.addActionListener(e -> bulk(true));
				allNotify.addActionListener(e -> bulk(false));
			}
			add(header, BorderLayout.NORTH);

			body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
			body.setOpaque(false);
			add(body, BorderLayout.CENTER);
			setOpen(open);
		}

		void setBody(JComponent c)
		{
			body.removeAll();
			body.add(c);
		}

		void addRow(Row r)
		{
			members.add(r);
			rows.put(r.id, r);
			body.add(r);
		}

		void setOpen(boolean o)
		{
			open = o;
			body.setVisible(o);
			title.setText((o ? "▾ " : "▸ ") + baseTitle);
			revalidate();
			repaint();
		}

		void bulk(boolean syncClicked)
		{
			if (updating)
			{
				return;
			}
			boolean on = syncClicked ? allSync.isSelected() : allNotify.isSelected();
			Map<String, Toggle> change = new HashMap<>();
			for (Row r : members)
			{
				Toggle t = plugin.toggle(r.id);
				boolean s = t.sync;
				boolean n = t.notify;
				if (syncClicked)
				{
					s = on;
					n = on && store.getBool("notifyDefault", true);
				}
				else if (on)
				{
					s = true;
					n = true;
				}
				else
				{
					n = false;
				}
				change.put(r.id, new Toggle(s, s && n));
			}
			plugin.setToggles(change);
			for (Row r : members)
			{
				r.show(change.get(r.id));
			}
			updateGroupHeaders();
		}

		void updateHeader()
		{
			boolean allS = !members.isEmpty();
			boolean allN = !members.isEmpty();
			int on = 0;
			for (Row r : members)
			{
				allS &= r.sync.isSelected();
				allN &= r.notify.isSelected();
				if (r.sync.isSelected())
				{
					on++;
				}
			}
			boolean was = updating;
			updating = true;
			allSync.setSelected(allS);
			allNotify.setSelected(allN);
			updating = was;
			title.setText((open ? "▾ " : "▸ ") + baseTitle + (on > 0 && impl != null ? "  • " + on + " on" : ""));
		}
	}

	private void updateGroupHeaders()
	{
		for (Group g : groups)
		{
			if (g.impl != null)
			{
				g.updateHeader();
			}
		}
	}

	// ---- actions

	private void quickSelect(int choice)
	{
		Map<String, Toggle> change = new HashMap<>();
		for (PatchDef p : data.patches())
		{
			Toggle old = plugin.toggle(p.key());
			change.put(p.key(), quick(choice, PatchTypes.isUsual(p.impl), old));
		}
		change.put(CONTRACT, quick(choice, true, plugin.toggle(CONTRACT)));
		plugin.setToggles(change);
		refresh();
	}

	private Toggle quick(int choice, boolean usual, Toggle old)
	{
		switch (choice)
		{
			case 1:
				return new Toggle(usual, usual);
			case 2:
				return new Toggle(true, old.sync ? old.notify : store.getBool("notifyDefault", true));
			case 3:
				return new Toggle(old.sync, old.sync);
			case 4:
				return new Toggle(old.sync, false);
			default:
				return new Toggle(false, false);
		}
	}

	/** Opens the settings block and the first few groups; used to look at the layout. */
	void openSome(int groupCount)
	{
		for (Component c : getComponents())
		{
			if (c instanceof Group && ((Group) c).impl == null)
			{
				((Group) c).setOpen(true);
			}
		}
		for (int i = 0; i < Math.min(groupCount, groups.size()); i++)
		{
			groups.get(i).setOpen(true);
		}
	}

	private void applyFilter()
	{
		String q = search.getText().trim().toLowerCase(Locale.ENGLISH);
		for (Group g : groups)
		{
			if (g.impl == null)
			{
				continue;
			}
			boolean any = false;
			for (Row r : g.members)
			{
				boolean m = r.matches(q) || g.baseTitle.toLowerCase(Locale.ENGLISH).contains(q);
				r.setVisible(m);
				any |= m;
			}
			g.setVisible(any);
			if (!q.isEmpty() && any)
			{
				g.setOpen(true);
			}
		}
		revalidate();
		repaint();
	}

	// ---- refreshing from the store

	/** Re-reads every switch; safe to call from any thread. */
	void refreshLater()
	{
		SwingUtilities.invokeLater(this::refresh);
	}

	void refresh()
	{
		for (Row r : rows.values())
		{
			r.show(plugin.toggle(r.id));
			if (r.id.equals(CONTRACT))
			{
				r.name.setText(plugin.contractLabel());
			}
		}
		updateGroupHeaders();
		showStatus(plugin.status().isEmpty() ? "Waiting for the first sync." : plugin.status());
	}

	void showStatus(String text)
	{
		status.setText("<html><div style='width:195px'>" + escape(text) + "</div></html>");
	}

	private static String escape(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
