package io.github.ibcmanager.ui;

import io.github.ibcmanager.config.IbcConfigSchema;
import io.github.ibcmanager.config.SettingDefinition;

import javax.swing.table.AbstractTableModel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@SuppressWarnings("serial")
public final class ProfileSettingsTableModel extends AbstractTableModel {
    private static final Set<String> PROFILE_CONTROLLED = Set.of(
            "IbLoginId", "IbPassword", "TradingMode", "MinimizeMainWindow",
            "OverrideTwsApiPort", "CommandServerPort", "BindAddress", "IbDir",
            "SecondFactorDevice");
    private final List<SettingDefinition> definitions;
    private final Map<String, String> values = new LinkedHashMap<>();

    public ProfileSettingsTableModel(Map<String, String> initial) {
        definitions = IbcConfigSchema.definitions().stream()
                .filter(definition -> !definition.sensitive())
                .filter(definition -> !PROFILE_CONTROLLED.contains(definition.key()))
                .toList();
        if (initial != null) values.putAll(initial);
    }

    @Override
    public int getRowCount() {
        return definitions.size();
    }

    @Override
    public int getColumnCount() {
        return 3;
    }

    @Override
    public String getColumnName(int column) {
        return switch (column) {
            case 0 -> "Category";
            case 1 -> "Setting";
            case 2 -> "Value";
            default -> "";
        };
    }

    @Override
    public Class<?> getColumnClass(int columnIndex) {
        return String.class;
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return columnIndex == 2;
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        SettingDefinition definition = definitions.get(rowIndex);
        return switch (columnIndex) {
            case 0 -> definition.category();
            case 1 -> definition.label() + "  (" + definition.key() + ")";
            case 2 -> values.getOrDefault(definition.key(), "");
            default -> "";
        };
    }

    @Override
    public void setValueAt(Object value, int rowIndex, int columnIndex) {
        if (columnIndex != 2) return;
        String key = definitions.get(rowIndex).key();
        String text = value == null ? "" : value.toString().trim();
        if (text.isEmpty()) values.remove(key);
        else values.put(key, text);
        fireTableCellUpdated(rowIndex, columnIndex);
    }

    public SettingDefinition definitionAt(int row) {
        return definitions.get(row);
    }

    public Map<String, String> settings() {
        Map<String, String> result = new LinkedHashMap<>();
        for (SettingDefinition definition : definitions) {
            if (values.containsKey(definition.key())) result.put(definition.key(), values.get(definition.key()));
        }
        return result;
    }

    public void reset(int row) {
        if (row < 0 || row >= definitions.size()) return;
        values.remove(definitions.get(row).key());
        fireTableCellUpdated(row, 2);
    }
}
