package io.github.ibcmanager.ui;

import io.github.ibcmanager.model.ValidationIssue;
import io.github.ibcmanager.validation.ValidationResult;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.util.List;

@SuppressWarnings("serial")
public final class ValidationDialog extends JDialog {
    private ValidationDialog(Frame owner, String profileName, ValidationResult result) {
        super(owner, "Validation - " + profileName, true);
        setLayout(new BorderLayout(8, 8));
        String summary = result.isValid()
                ? "No blocking errors. " + result.warningCount() + " warning(s)."
                : result.errorCount() + " error(s), " + result.warningCount() + " warning(s).";
        JLabel heading = new JLabel(summary);
        heading.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 10, 0, 10));
        add(heading, BorderLayout.NORTH);
        JTable table = new JTable(new IssueTableModel(result.issues()));
        table.setAutoCreateRowSorter(true);
        table.setRowHeight(25);
        table.getColumnModel().getColumn(0).setPreferredWidth(90);
        table.getColumnModel().getColumn(1).setPreferredWidth(160);
        table.getColumnModel().getColumn(2).setPreferredWidth(550);
        add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton close = new JButton("Close");
        close.addActionListener(event -> dispose());
        buttons.add(close);
        add(buttons, BorderLayout.SOUTH);
        setMinimumSize(new Dimension(650, 340));
        setSize(900, 480);
        setLocationRelativeTo(owner);
    }

    public static void show(Frame owner, String profileName, ValidationResult result) {
        new ValidationDialog(owner, profileName, result).setVisible(true);
    }

    private static final class IssueTableModel extends AbstractTableModel {
        private final List<ValidationIssue> issues;

        private IssueTableModel(List<ValidationIssue> issues) {
            this.issues = List.copyOf(issues);
        }

        @Override public int getRowCount() { return issues.size(); }
        @Override public int getColumnCount() { return 3; }
        @Override public String getColumnName(int column) {
            return switch (column) { case 0 -> "Severity"; case 1 -> "Field"; case 2 -> "Message"; default -> ""; };
        }
        @Override public Object getValueAt(int rowIndex, int columnIndex) {
            ValidationIssue issue = issues.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> issue.severity();
                case 1 -> issue.field();
                case 2 -> issue.message();
                default -> "";
            };
        }
    }
}
