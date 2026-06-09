package com.charlie.astro;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

public class AstroFrame extends JFrame {
    private static final DateTimeFormatter UK_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final JTextField dateField = text("09/06/2026", 12);
    private final JSpinner daySpinner = new JSpinner(new SpinnerNumberModel(LocalDate.now().getDayOfMonth(), 1, 31, 1));
    private final JSpinner monthSpinner = new JSpinner(new SpinnerNumberModel(LocalDate.now().getMonthValue(), 1, 12, 1));
    private final JSpinner yearSpinner = new JSpinner(new SpinnerNumberModel(LocalDate.now().getYear(), 1, 9999, 1));
    private final JTextField latitudeField = text("53.703700", 14);
    private final JTextField longitudeField = text("-0.877700", 14);
    private final JTextField heightField = text("0.0", 10);
    private final JTextField zoneField = text(ZoneId.systemDefault().getId(), 18);
    private final JTextField ephePathField = text("", 22);
    private final JCheckBox useSwiss = new JCheckBox("Use GPL Swiss Ephemeris", true);
    private final JLabel engineLabel = new JLabel();

    private final DefaultTableModel model = new DefaultTableModel(
            new String[]{"Body", "Event", "Local time", "UTC time", "Engine", "Estimated accuracy", "Notes"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };

    public AstroFrame() {
        super("Sunrise, Sunset, Moonrise & Moonset - GPL Swiss Ephemeris");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1280, 720));
        buildUi();
        syncDateFieldFromSpinners();
        setLocationRelativeTo(null);
        calculate();
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(16, 16));
        root.setBackground(new Color(248, 249, 250));
        root.setBorder(new EmptyBorder(20, 20, 20, 20));
        setContentPane(root);

        JLabel title = new JLabel("Astronomical Rise / Set Calculator");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 28f));
        title.setForeground(new Color(33, 37, 41));
        root.add(title, BorderLayout.NORTH);

        JPanel content = new JPanel(new BorderLayout(16, 16));
        content.setOpaque(false);
        root.add(content, BorderLayout.CENTER);

        RoundedPanel card = new RoundedPanel(18);
        card.setLayout(new GridBagLayout());
        card.setBorder(new EmptyBorder(18, 18, 18, 18));
        content.add(card, BorderLayout.WEST);

        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(7, 7, 7, 7);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;

        addHeading(card, gc, "Input", 0);
        addRow(card, gc, "Date dd/mm/yyyy", dateField, 1);

        JPanel cal = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        cal.setOpaque(false);
        cal.add(new JLabel("Day")); cal.add(daySpinner);
        cal.add(new JLabel("Month")); cal.add(monthSpinner);
        cal.add(new JLabel("Year")); cal.add(yearSpinner);
        styleSpinner(daySpinner); styleSpinner(monthSpinner); styleSpinner(yearSpinner);
        addRow(card, gc, "Calendar", cal, 2);

        addRow(card, gc, "Latitude", latitudeField, 3);
        addRow(card, gc, "Longitude", longitudeField, 4);
        addRow(card, gc, "Observer height, metres", heightField, 5);
        addRow(card, gc, "Time zone", zoneField, 6);
//        addRow(card, gc, "Swiss ephemeris path", ephePathField, 7);

        useSwiss.setOpaque(false);
        useSwiss.setSelected(true);
        useSwiss.setEnabled(false);
        addRow(card, gc, "Engine", useSwiss, 8);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.setOpaque(false);
        JButton calc = new RoundedButton("Calculate", new Color(13, 110, 253), Color.WHITE);
        JButton utc = new RoundedButton("Use UTC", new Color(108, 117, 125), Color.WHITE);
        JButton local = new RoundedButton("Use computer zone", new Color(25, 135, 84), Color.WHITE);
        buttons.add(calc); buttons.add(utc); buttons.add(local);
        gc.gridx = 0; gc.gridy = 9; gc.gridwidth = 2;
        card.add(buttons, gc);

        JTextArea help = new JTextArea("Lat/long accepts decimals; 6 decimal places is about 0.11 m at the equator.\n"
                + "Height lowers the geometric sea horizon by about acos(R/(R+h)).\n"
                + "Atmospheric refraction is deliberately NOT used.\n"
                + "Swiss Ephemeris is now a normal Maven dependency, not an optional profile.\n"
                + "The code fixes the previous topocentric flag problem by using Swiss geocentric positions, then applying a WGS-84 topocentric/parallax correction itself.\n"
                + "For maximum accuracy, download Swiss Ephemeris data files and enter their folder above.\n"
                + "The ± seconds are conservative estimates, not official observatory certification.");
        help.setWrapStyleWord(true); help.setLineWrap(true); help.setEditable(false);
        help.setBackground(new Color(233, 236, 239));
        help.setBorder(new EmptyBorder(12, 12, 12, 12));
        help.setForeground(new Color(73, 80, 87));
        gc.gridx = 0; gc.gridy = 10; gc.gridwidth = 2; gc.weightx = 1;
        card.add(help, gc);

        JTable table = new JTable(model);
        table.setRowHeight(34);
        table.setFont(table.getFont().deriveFont(14f));
        table.getTableHeader().setFont(table.getTableHeader().getFont().deriveFont(Font.BOLD, 14f));
        table.setFillsViewportHeight(true);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(222, 226, 230)));
        content.add(scroll, BorderLayout.CENTER);

        engineLabel.setForeground(new Color(108, 117, 125));
        root.add(engineLabel, BorderLayout.SOUTH);

        calc.addActionListener(e -> calculate());
        utc.addActionListener(e -> zoneField.setText("UTC"));
        local.addActionListener(e -> zoneField.setText(ZoneId.systemDefault().getId()));
        daySpinner.addChangeListener(e -> syncDateFieldFromSpinners());
        monthSpinner.addChangeListener(e -> syncDateFieldFromSpinners());
        yearSpinner.addChangeListener(e -> syncDateFieldFromSpinners());
    }

    private void addHeading(JPanel panel, GridBagConstraints gc, String text, int row) {
        gc.gridx = 0; gc.gridy = row; gc.gridwidth = 2;
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 18f));
        panel.add(label, gc);
        gc.gridwidth = 1;
    }

    private void addRow(JPanel panel, GridBagConstraints gc, String label, Component comp, int row) {
        gc.gridx = 0; gc.gridy = row; gc.gridwidth = 1; gc.weightx = 0;
        JLabel jLabel = new JLabel(label);
        jLabel.setFont(jLabel.getFont().deriveFont(Font.BOLD));
        panel.add(jLabel, gc);
        gc.gridx = 1; gc.weightx = 1;
        panel.add(comp, gc);
    }

    private JTextField text(String value, int columns) {
        JTextField f = new JTextField(value, columns);
        styleText(f);
        return f;
    }

    private void styleText(JTextField field) {
        field.setFont(field.getFont().deriveFont(15f));
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(206, 212, 218)),
                new EmptyBorder(8, 10, 8, 10)));
    }

    private void styleSpinner(JSpinner spinner) {
        spinner.setPreferredSize(new Dimension(82, 38));
        JComponent editor = spinner.getEditor();
        if (editor instanceof JSpinner.DefaultEditor de) styleText(de.getTextField());
    }

    private void syncDateFieldFromSpinners() {
        int day = (int) daySpinner.getValue();
        int month = (int) monthSpinner.getValue();
        int year = (int) yearSpinner.getValue();
        LocalDate first = LocalDate.of(year, month, 1);
        LocalDate date = LocalDate.of(year, month, Math.min(day, first.lengthOfMonth()));
        dateField.setText(date.format(UK_DATE));
    }

    private void calculate() {
        try {
            LocalDate date = LocalDate.parse(dateField.getText().trim(), UK_DATE);
            double lat = Double.parseDouble(latitudeField.getText().trim());
            double lon = Double.parseDouble(longitudeField.getText().trim());
            double height = Double.parseDouble(heightField.getText().trim());
            ZoneId zone = ZoneId.of(zoneField.getText().trim());
            if (lat < -90 || lat > 90) throw new IllegalArgumentException("Latitude must be between -90 and +90.");
            if (lon < -180 || lon > 180) throw new IllegalArgumentException("Longitude must be between -180 and +180.");
            if (height < -500 || height > 100000) throw new IllegalArgumentException("Height should be metres above local datum, roughly -500 to 100000.");

            RiseSetCalculator.EngineResult engine = RiseSetCalculator.engine(true, ephePathField.getText().trim(), lat, lon, height);
            engineLabel.setText(engine.description());

            model.setRowCount(0);
            List<RiseSetCalculator.EventResult> results = RiseSetCalculator.calculate(date, lat, lon, height, zone, engine.provider());
            for (RiseSetCalculator.EventResult r : results) {
                model.addRow(new Object[]{r.body(), r.event(), r.localTime(), r.utcTime(), r.engine(), r.estimatedAccuracy(), r.note()});
            }
        } catch (DateTimeParseException ex) {
            error("Please enter the date as dd/mm/yyyy, for example 09/06/2026.");
        } catch (Exception ex) {
            error(ex.getMessage());
        }
    }

    private void error(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Input error", JOptionPane.ERROR_MESSAGE);
    }

    private static class RoundedPanel extends JPanel {
        private final int radius;
        RoundedPanel(int radius) { this.radius = radius; setOpaque(false); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Color.WHITE);
            g2.fill(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), radius, radius));
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private static class RoundedButton extends JButton {
        private final Color bg;
        private final Color fg;
        RoundedButton(String text, Color bg, Color fg) {
            super(text); this.bg = bg; this.fg = fg;
            setFocusPainted(false); setBorder(new EmptyBorder(10, 16, 10, 16));
            setContentAreaFilled(false); setOpaque(false); setForeground(fg);
            setFont(getFont().deriveFont(Font.BOLD, 14f));
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(bg);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
            g2.dispose();
            super.paintComponent(g);
        }
    }
}
