package io.github.ibcmanager.ui;

import io.github.ibcmanager.config.IbcCompatibilityPolicy;
import io.github.ibcmanager.config.IbcConfigSchema;
import io.github.ibcmanager.config.ManagedConfigService;
import io.github.ibcmanager.config.SettingDefinition;

import javax.swing.table.AbstractTableModel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SuppressWarnings("serial")
public final class ProfileSettingsTableModel extends AbstractTableModel {
    private final List<SettingDefinition> definitions;
    private final Map<String, String> values = new LinkedHashMap<>();
    private final Map<String, String> passthrough = new LinkedHashMap<>();

    public ProfileSettingsTableModel(Map<String, String> initial) {
        definitions = IbcConfigSchema.definitions().stream()
                .filter(definition -> !definition.sensitive())
                .filter(definition -> !ManagedConfigService.isProfileControlled(definition.key()))
                .filter(definition -> !IbcCompatibilityPolicy.isUnsupportedStructuredSetting(definition.key()))
                .toList();
        if (initial == null) return;
        for (Map.Entry<String, String> entry : initial.entrySet()) {
            String key = entry.getKey();
            if (key == null || IbcConfigSchema.isSensitive(key)
                    || ManagedConfigService.isProfileControlled(key)
                    || IbcCompatibilityPolicy.isUnsupportedStructuredSetting(key)) continue;
            var definition = IbcConfigSchema.findIgnoreCase(key);
            if (definition.isPresent()) {
                values.put(definition.get().key(), entry.getValue() == null ? "" : entry.getValue());
            } else {
                passthrough.put(key, entry.getValue() == null ? "" : entry.getValue());
            }
        }
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
        result.putAll(passthrough);
        return result;
    }

    public void reset(int row) {
        if (row < 0 || row >= definitions.size()) return;
        values.remove(definitions.get(row).key());
        fireTableCellUpdated(row, 2);
    }
}
