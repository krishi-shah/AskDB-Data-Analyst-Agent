package ca.yorku.askdb.presentation.gui;

import ca.yorku.askdb.app.AskDBFacade;
import ca.yorku.askdb.config.Settings;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

/** Routing policy and sample sharing (F12). Shows whether keys are set, never the keys themselves. */
public class SettingsDialog extends JDialog {
    static final Map<String, String> POLICY_LABELS = new LinkedHashMap<>();

    static {
        POLICY_LABELS.put("local_only", "Local Only (all data stays on this computer)");
        POLICY_LABELS.put("cheap_first", "Cheap First (fast cloud model, strong model for hard repairs)");
        POLICY_LABELS.put("strong_only", "Strong Only (strong cloud model for every call)");
    }

    private final GuiController controller;
    private final JComboBox<String> policy = new JComboBox<>(POLICY_LABELS.values().toArray(new String[0]));
    private final JCheckBox share = new JCheckBox("Send a few sample rows and column values to the model");

    public SettingsDialog(GuiController controller, JFrame parent) {
        super(parent, "AskDB Settings", true);
        this.controller = controller;
        AskDBFacade facade = controller.getFacade();
        Settings s = facade.getSettings();
        policy.setSelectedIndex(List.copyOf(POLICY_LABELS.keySet()).indexOf(facade.getRoutingPolicy()));
        share.setSelected(s.shareSamples);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 4, 12));
        int row = 0;
        addRow(form, row++, "Routing policy", policy);
        addRow(form, row++, "", share);
        addRow(form, row++, "Local model", new JLabel(s.ollamaModel + " at " + s.ollamaUrl));
        addRow(form, row++, "Gemini (fast tier)", new JLabel(keyText(s.geminiApiKey, s.geminiModel)));
        addRow(form, row++, "Groq (strong tier)", new JLabel(keyText(s.groqApiKey, s.groqModel)));
        JLabel note = new JLabel("<html><body style='width:420px'>API keys are read from the .env file in the folder "
                + "AskDB was started from. Without a key, the cloud tiers fall back to the local model.</body></html>");
        GridBagConstraints c = constraints(0, row);
        c.gridwidth = 2;
        c.insets = new Insets(10, 4, 4, 4);
        form.add(note, c);

        JButton ok = new JButton("OK");
        ok.addActionListener(e -> apply());
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(ok);
        buttons.add(cancel);

        setLayout(new BorderLayout());
        add(form, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(ok);
        pack();
        setLocationRelativeTo(parent);
    }

    private static void addRow(JPanel form, int row, String label, java.awt.Component field) {
        form.add(new JLabel(label), constraints(0, row));
        GridBagConstraints c = constraints(1, row);
        c.weightx = 1;
        form.add(field, c);
    }

    private static GridBagConstraints constraints(int x, int y) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x;
        c.gridy = y;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(4, 4, 4, 4);
        return c;
    }

    private static String keyText(String key, String model) {
        return key != null && !key.isEmpty() ? "key set, model " + model : "no key set (optional)";
    }

    private void apply() {
        String name = List.copyOf(POLICY_LABELS.keySet()).get(policy.getSelectedIndex());
        List<String> warnings = controller.onPolicySelected(name);
        controller.onShareSamplesChanged(share.isSelected());
        if (!warnings.isEmpty()) {
            JOptionPane.showMessageDialog(this, String.join("\n", warnings), "Cloud models unavailable",
                    JOptionPane.INFORMATION_MESSAGE);
        }
        dispose();
    }
}
