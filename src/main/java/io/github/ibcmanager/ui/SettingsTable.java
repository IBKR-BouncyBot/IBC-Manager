package io.github.ibcmanager.ui;

import io.github.ibcmanager.config.SettingDefinition;

import javax.swing.JComboBox;
import javax.swing.JTable;
import javax.swing.table.TableCellEditor;
import javax.swing.DefaultCellEditor;
import java.awt.event.MouseEvent;

@SuppressWarnings("serial")
public final class SettingsTable extends JTable {
    private final ProfileSettingsTableModel settingsModel;

    public SettingsTable(ProfileSettingsTableModel model) {
        super(model);
        settingsModel = model;
        setRowHeight(25);
        setAutoCreateRowSorter(true);
        getColumnModel().getColumn(0).setPreferredWidth(110);
        getColumnModel().getColumn(1).setPreferredWidth(330);
        getColumnModel().getColumn(2).setPreferredWidth(180);
        getColumnModel().getColumn(3).setPreferredWidth(120);
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        if (event.getX() < 0 || event.getY() < 0
                || event.getX() >= getWidth() || event.getY() >= getHeight()) return null;
        int viewRow = rowAtPoint(event.getPoint());
        if (viewRow < 0) return null;
        int modelRow = convertRowIndexToModel(viewRow);
        SettingDefinition definition = settingsModel.definitionAt(modelRow);
        return "<html><b>" + escape(definition.key()) + "</b><br>"
                + escape(definition.description()) + "<br>Included configuration default: "
                + escape(settingsModel.includedDefaultAt(modelRow).isBlank() ? "blank" : settingsModel.includedDefaultAt(modelRow))
                + "<br>Blank uses the engine fallback or preserves Gateway's setting; it is not a factory reset.</html>";
    }

    @Override
    public TableCellEditor getCellEditor(int row, int column) {
        if (convertColumnIndexToModel(column) == 2) {
            SettingDefinition definition = settingsModel.definitionAt(convertRowIndexToModel(row));
            if (!definition.allowedValues().isEmpty()) {
                java.util.List<String> choices = new java.util.ArrayList<>(definition.allowedValues());
                if (!choices.contains("")) choices.add(0, "");
                JComboBox<String> combo = new JComboBox<>(choices.toArray(String[]::new));
                combo.setEditable(false);
                return new DefaultCellEditor(combo);
            }
        }
        return super.getCellEditor(row, column);
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
