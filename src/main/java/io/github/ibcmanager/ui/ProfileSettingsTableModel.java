package io.github.ibcmanager.ui;

import io.github.ibcmanager.config.IbcConfigSchema;
import io.github.ibcmanager.config.IbcConfigDocument;
import io.github.ibcmanager.config.ProfileConfiguration;
import io.github.ibcmanager.config.SettingDefinition;
import io.github.ibcmanager.config.SettingType;

import javax.swing.table.AbstractTableModel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One editable engine-settings surface, showing the exact value generated for launch. */
@SuppressWarnings("serial")
public final class ProfileSettingsTableModel extends AbstractTableModel {
    private final List<SettingDefinition> definitions = new ArrayList<>();
    private final Map<String, String> values = new LinkedHashMap<>();
    private final Map<String, String> defaults;

    public ProfileSettingsTableModel(Map<String, String> initial) {
        try { defaults = ProfileConfiguration.template().activeSettings(); }
        catch (IOException failure) { throw new IllegalStateException("Cannot read included defaults", failure); }
        IbcConfigSchema.definitions().stream()
                .filter(definition -> ProfileConfiguration.editableEngineSetting(definition.key()))
                .forEach(definitions::add);
        if (initial == null) return;
        for (var entry : initial.entrySet()) {
            if (!ProfileConfiguration.editableEngineSetting(entry.getKey())) continue;
            String key = IbcConfigSchema.canonicalKey(entry.getKey());
            values.put(key, entry.getValue() == null ? "" : entry.getValue());
            if (IbcConfigSchema.find(key).isEmpty()) definitions.add(customDefinition(key));
        }
    }

    @Override public int getRowCount() { return definitions.size(); }
    @Override public int getColumnCount() { return 4; }
    @Override public String getColumnName(int column) {
        return switch (column) {
            case 0 -> "Category";
            case 1 -> "Setting";
            case 2 -> "Value";
            case 3 -> "Value source";
            default -> "";
        };
    }
    @Override public Class<?> getColumnClass(int columnIndex) { return String.class; }
    @Override public boolean isCellEditable(int rowIndex, int columnIndex) { return columnIndex == 2; }
    @Override public Object getValueAt(int rowIndex, int columnIndex) {
        SettingDefinition definition = definitions.get(rowIndex);
        return switch (columnIndex) {
            case 0 -> definition.category();
            case 1 -> definition.label() + "  (" + definition.key() + ")";
            case 2 -> values.getOrDefault(definition.key(), defaults.getOrDefault(definition.key(), ""));
            case 3 -> values.containsKey(definition.key()) ? "Profile" : "Included default";
            default -> "";
        };
    }
    @Override public void setValueAt(Object value, int rowIndex, int columnIndex) {
        if (columnIndex != 2) return;
        // Blank is an intentional engine value. Use Reset for the included default.
        values.put(definitions.get(rowIndex).key(), value == null ? "" : value.toString().trim());
        fireTableRowsUpdated(rowIndex, rowIndex);
    }
    public SettingDefinition definitionAt(int row) { return definitions.get(row); }
    public String includedDefaultAt(int row) { return defaults.getOrDefault(definitions.get(row).key(), ""); }
    public Map<String, String> settings() {
        Map<String, String> result = new LinkedHashMap<>();
        for (var definition : definitions) {
            if (values.containsKey(definition.key())) result.put(definition.key(), values.get(definition.key()));
        }
        return result;
    }

    public void reset(int row) {
        if (row < 0 || row >= definitions.size()) return;
        String key = definitions.get(row).key();
        values.remove(key);
        if (IbcConfigSchema.find(key).isEmpty()) {
            definitions.remove(row);
            fireTableRowsDeleted(row, row);
        } else fireTableRowsUpdated(row, row);
    }

    public void addProperty(String rawKey, String value) {
        String key = IbcConfigSchema.canonicalKey(rawKey == null ? "" : rawKey.trim());
        // Reuse the actual configuration-key validation, not a looser UI regex.
        IbcConfigDocument.parse("").set(key, value == null ? "" : value);
        if (!ProfileConfiguration.editableEngineSetting(key)) {
            throw new IllegalArgumentException("This property is generated, sensitive or not supported by Gateway");
        }
        if (definitions.stream().noneMatch(d -> d.key().equals(key))) {
            definitions.add(customDefinition(key));
            fireTableRowsInserted(definitions.size() - 1, definitions.size() - 1);
        }
        values.put(key, value == null ? "" : value);
        fireTableDataChanged();
    }

    private static SettingDefinition customDefinition(String key) {
        return new SettingDefinition(key, key, "Additional", SettingType.TEXT, List.of(), "",
                "Additional engine property retained in this profile. Unrecognized engine properties may have no effect.",
                false, true);
    }
}
