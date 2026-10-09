package io.github.ibcmanager.tests;

import io.github.ibcmanager.app.AppPaths;
import io.github.ibcmanager.app.AppServices;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.ui.MainFrame;
import io.github.ibcmanager.ui.AboutDialog;
import io.github.ibcmanager.ui.ProfileEditorDialog;
import io.github.ibcmanager.ui.UiTheme;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JList;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JMenu;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class GuiSmokeRunner {
    private GuiSmokeRunner() { }

    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("GUI smoke requires an interactive display or Xvfb");
        }
        if (args.length != 0 && (args.length != 2 || !args[0].equals("--screenshot"))) {
            throw new IllegalArgumentException("Usage: GuiSmokeRunner [--screenshot output.png]");
        }
        Path screenshot = args.length == 2 ? Path.of(args[1]) : null;
        Path root = TestSupport.tempDirectory("gui-smoke");
        String previous = System.getProperty("ibcmanager.suppressFirstRunWizard");
        System.setProperty("ibcmanager.suppressFirstRunWizard", "true");
        try {
            AppPaths paths = new AppPaths(root.resolve("data"));
            Profile profile = TestSupport.validProfile(root.resolve("install"));
            try (AppServices services = AppServices.create(paths)) {
                services.profileRepository().save(profile);
                AtomicReference<MainFrame> frameRef = new AtomicReference<>();
                AtomicReference<ProfileEditorDialog> profileDialogRef = new AtomicReference<>();
                AtomicReference<Throwable> failure = new AtomicReference<>();
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        UiTheme.install();
                        MainFrame frame = new MainFrame(services);
                        frameRef.set(frame);
                        frame.setVisible(true);
                        Constructor<ProfileEditorDialog> constructor = ProfileEditorDialog.class
                                .getDeclaredConstructor(java.awt.Frame.class, Profile.class, boolean.class);
                        constructor.setAccessible(true);
                        ProfileEditorDialog profileDialog = constructor.newInstance(frame, null, false);
                        profileDialog.setModal(false);
                        profileDialogRef.set(profileDialog);
                        profileDialog.setVisible(true);
                    } catch (Throwable throwable) {
                        failure.set(throwable);
                    }
                });
                if (failure.get() != null) throw new AssertionError("Could not create real main window", failure.get());
                Thread.sleep(750);
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        MainFrame frame = frameRef.get();
                        require(frame != null, "main frame was not created");
                        require(frame.isShowing(), "main frame is not visible");
                        require(frame.getTitle().startsWith("IBC Manager 2.0.3"), "window title is incorrect");
                        require(frame.getJMenuBar() != null && frame.getJMenuBar().getMenuCount() == 3,
                                "menu bar is incomplete");
                        JMenu tools = frame.getJMenuBar().getMenu(1);
                        boolean forceStopPresent = false;
                        for (int index = 0; index < tools.getItemCount(); index++) {
                            if (tools.getItem(index) != null
                                    && "Force stop selected profile...".equals(tools.getItem(index).getText())) {
                                forceStopPresent = true;
                            }
                        }
                        for (int index = 0; index < tools.getItemCount(); index++) {
                            if (tools.getItem(index) != null) require(!tools.getItem(index).getText().contains("managed config"),
                                    "raw config menu must not exist");
                        }
                        require(forceStopPresent, "force-stop recovery action is missing from Tools menu");
                        List<Component> components = descendants(frame);
                        require(components.stream().filter(JLabel.class::isInstance).map(JLabel.class::cast)
                                        .noneMatch(label -> label.getText() != null
                                                && (label.getText().contains("API listener detected means")
                                                || label.getText().contains("handshake is not verified"))),
                                "permanent API disclaimer must not be displayed");
                        JLabel apiValue = named(components, "profileApiValue", JLabel.class);
                        require(apiValue.getToolTipText().contains("Your trading application verifies"),
                                "API verification explanation remains available on hover");
                        JList<?> profileList = components.stream().filter(JList.class::isInstance)
                                .map(JList.class::cast).findFirst().orElseThrow();
                        require(profileList.getModel().getSize() == 1, "saved profile was not loaded into dashboard");
                        require(profileList.getSelectedIndex() == 0, "first profile was not selected");
                        JTabbedPane tabs = components.stream().filter(JTabbedPane.class::isInstance)
                                .map(JTabbedPane.class::cast).findFirst().orElseThrow();
                        require(tabs.getTabCount() == 3, "dashboard detail tabs are incomplete");
                        require("Overview".equals(tabs.getTitleAt(0)), "overview tab missing");
                        require("Logs".equals(tabs.getTitleAt(1)), "logs tab missing");
                        require("Commands".equals(tabs.getTitleAt(2)), "commands tab missing");
                        Component statusIndicator = components.stream()
                                .filter(component -> "profileStatusIndicator".equals(component.getName()))
                                .findFirst().orElseThrow();
                        require(statusIndicator.isVisible(), "profile status indicator is not visible");
                        require(statusIndicator.getAccessibleContext().getAccessibleDescription() != null
                                        && statusIndicator.getAccessibleContext().getAccessibleDescription().contains("stopped"),
                                "profile status indicator lacks explicit state text");
                        require(components.stream().filter(JButton.class::isInstance)
                                        .map(JButton.class::cast)
                                        .noneMatch(button -> "Enable API".equals(button.getText())),
                                "TWS-only ENABLEAPI action must not be offered by a Gateway-only product");
                        JButton start = named(components, "startProfileButton", JButton.class);
                        JButton stop = named(components, "stopProfileButton", JButton.class);
                        JButton forceStop = named(components, "forceStopProfileButton", JButton.class);
                        JButton restart = named(components, "restartProfileButton", JButton.class);
                        JButton pause = named(components, "pauseProfileButton", JButton.class);
                        for (JButton action : List.of(start, stop, forceStop, restart, pause)) {
                            require(action.getWidth() >= action.getPreferredSize().width,
                                    action.getText() + " button was compressed horizontally");
                            require(action.getHeight() >= action.getPreferredSize().height,
                                    action.getText() + " button was compressed vertically");
                            require(action.getPreferredSize().width >= 112,
                                    action.getText() + " button is not visibly wide enough");
                            require(action.getPreferredSize().height >= 38,
                                    action.getText() + " button is not visibly tall enough");
                            require(action.getFont().isBold(), action.getText() + " button is not visually emphasized");
                        }
                        List<JButton> actions = List.of(start, stop, forceStop, restart, pause);
                        for (int first = 0; first < actions.size(); first++) {
                            for (int second = first + 1; second < actions.size(); second++) {
                                require(!actions.get(first).getForeground().equals(actions.get(second).getForeground()),
                                        actions.get(first).getText() + " and " + actions.get(second).getText()
                                                + " buttons are not visually distinct");
                            }
                        }

                        require(components.stream().filter(JButton.class::isInstance).map(JButton.class::cast)
                                .noneMatch(b -> b.getText().equals("Managed config")), "no duplicate configuration toolbar");
                        JDialog profileDialog = profileDialogRef.get();
                        require(profileDialog != null && profileDialog.isShowing(),
                                "profile editor was not created and displayed");
                        List<Component> profileComponents = descendants(profileDialog);
                        require(profileComponents.stream().filter(JLabel.class::isInstance).map(JLabel.class::cast)
                                .noneMatch(l -> l.getText().contains("Existing/base config")
                                        || l.getText().contains("After IBC exits on 2FA")), "no hidden external or obsolete policy controls");
                        javax.swing.JTable settings = profileComponents.stream().filter(javax.swing.JTable.class::isInstance)
                                .map(javax.swing.JTable.class::cast).findFirst().orElseThrow();
                        require(settings.getColumnCount() == 4, "effective values and their source are shown");
                        JTabbedPane profileTabs = profileComponents.stream().filter(JTabbedPane.class::isInstance)
                                .map(JTabbedPane.class::cast).findFirst().orElseThrow();
                        require(profileTabs.indexOfTab("Windows startup") >= 0, "startup settings are inside Profile");
                        JButton detect = named(profileComponents, "detectInstallationsButton", JButton.class);
                        Component engine = profileComponents.stream()
                                .filter(component -> "integratedEngineLabel".equals(component.getName()))
                                .findFirst().orElseThrow();
                        require(engine.isVisible(), "included-engine label is not visible");
                        require(profileComponents.stream()
                                        .noneMatch(component -> "installIbcButton".equals(component.getName())),
                                "external IBC installer must not be offered");
                        JTextField secondFactor = named(profileComponents, "secondFactorDeviceField", JTextField.class);
                        JCheckBox autoRecovery = named(profileComponents,
                                "autoRecoverStartupStallBox", JCheckBox.class);
                        JCheckBox secondFactorRetry = named(profileComponents,
                                "secondFactorRetryBox", JCheckBox.class);
                        require(detect.getWidth() >= detect.getPreferredSize().width,
                                "detect-installations button was compressed horizontally");
                        require(detect.getHeight() >= detect.getPreferredSize().height,
                                "detect-installations button was compressed vertically");
                        require(secondFactor.isVisible(), "SecondFactorDevice field is not visible on the profile tab");
                        require(autoRecovery.isVisible(),
                                "automatic stalled-start recovery control is not visible on the profile tab");
                        require(autoRecovery.isSelected(),
                                "new profiles must default to unattended stalled-start recovery");
                        require(secondFactorRetry.isVisible(),
                                "automatic five-minute 2FA retry control is not visible on the profile tab");
                        require(secondFactorRetry.isSelected(),
                                "new profiles must default to repeated 2FA phone notifications");
                        require(secondFactorRetry.getText().contains("5 minutes"),
                                "the 2FA retry control must state the configured threshold");
                        require(secondFactorRetry.getToolTipText().contains("independent retry timer"),
                                "2FA timing must describe the maintained engine timer");
                        require(profileComponents.stream().anyMatch(JScrollPane.class::isInstance),
                                "profile editor must provide scrolling instead of compressing controls");
                        profileTabs.setSelectedIndex(profileTabs.indexOfTab("Gateway settings"));
                        io.github.ibcmanager.ui.ProfileSettingsTableModel tableModel =
                                (io.github.ibcmanager.ui.ProfileSettingsTableModel) settings.getModel();
                        int resetRow = -1;
                        for (int row = 0; row < tableModel.getRowCount(); row++) {
                            if (tableModel.definitionAt(row).key().equals("LoginDialogDisplayTimeout")) resetRow = row;
                        }
                        require(resetRow >= 0, "login timeout setting exists");
                        int viewRow = settings.convertRowIndexToView(resetRow);
                        settings.setRowSelectionInterval(viewRow, viewRow);
                        require(settings.editCellAt(viewRow, 2), "editable timeout value");
                        ((JTextField) settings.getEditorComponent()).setText("999");
                        profileComponents.stream().filter(JButton.class::isInstance).map(JButton.class::cast)
                                .filter(b -> b.getText().equals("Reset selected to included default"))
                                .findFirst().orElseThrow().doClick();
                        require(!settings.isEditing(), "reset cancels the stale cell editor");
                        require("60".equals(tableModel.getValueAt(resetRow, 2)), "reset cannot be overwritten by an old cell value");
                        profileDialog.dispose();
                        require(!profileDialog.isDisplayable(), "profile editor did not dispose cleanly");

                        assertConfirmationDialog(start, "Confirm start");
                        stop.setEnabled(true);
                        assertConfirmationDialog(stop, "Confirm stop");
                        restart.setEnabled(true);
                        assertConfirmationDialog(restart, "Confirm restart");
                        pause.setEnabled(true);
                        assertConfirmationDialog(pause, "Confirm pause");
                        require(services.runtimeRegistry().controller(profile.id())
                                        .map(controller -> !controller.status().processAlive()).orElse(false),
                                "cancelling a session confirmation must not start or change the process");

                        JDialog about = AboutDialog.create(frame);
                        about.setModal(false);
                        about.setVisible(true);
                        require(about.isShowing(), "About window displayed");
                        List<Component> aboutComponents = descendants(about);
                        JLabel thanks = named(aboutComponents, "ibcAuthorAcknowledgement", JLabel.class);
                        require(thanks.isShowing(), "IBC acknowledgement visible");
                        require(thanks.getText().contains("Richard L King (rlktradewright)"),
                                "About explicitly credits the upstream author");
                        require(thanks.getText().contains("Thank you"), "About expresses thanks");
                        require(aboutComponents.stream().filter(JLabel.class::isInstance).map(JLabel.class::cast)
                                .anyMatch(label -> label.getText().contains("Steven M. Kearns")),
                                "other upstream contributors also credited");
                        aboutComponents.stream().filter(JButton.class::isInstance).map(JButton.class::cast)
                                .filter(button -> "Close".equals(button.getText())).findFirst().orElseThrow().doClick();
                        require(!about.isDisplayable(), "About Close disposes cleanly");

                        if (screenshot == null) {
                            frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
                            require(!frame.isDisplayable(), "normal close path did not dispose the window");
                        }
                    } catch (Throwable throwable) {
                        failure.set(throwable);
                    }
                });
                if (failure.get() != null) throw new AssertionError("GUI smoke assertion failed", failure.get());
                if (screenshot != null) GuiScreenshotFixture.capture(frameRef.get(), services, profile, screenshot);
            }
            System.out.println("GUI SMOKE PASSED");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                for (Window window : Window.getWindows()) window.dispose();
            });
            if (previous == null) System.clearProperty("ibcmanager.suppressFirstRunWizard");
            else System.setProperty("ibcmanager.suppressFirstRunWizard", previous);
            TestSupport.deleteTree(root);
        }
    }

    private static void assertConfirmationDialog(JButton button, String expectedTitle) {
        AtomicBoolean displayed = new AtomicBoolean();
        Timer closer = new Timer(40, null);
        closer.addActionListener(event -> {
            for (Window window : Window.getWindows()) {
                if (window instanceof JDialog dialog && dialog.isShowing()
                        && expectedTitle.equals(dialog.getTitle())) {
                    displayed.set(true);
                    dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
                    if (dialog.isShowing()) dialog.dispose();
                    closer.stop();
                    return;
                }
            }
        });
        closer.setRepeats(true);
        closer.start();
        button.doClick();
        closer.stop();
        require(displayed.get(), button.getText() + " did not display " + expectedTitle);
    }

    private static List<Component> descendants(Container root) {
        List<Component> result = new ArrayList<>();
        for (Component component : root.getComponents()) {
            result.add(component);
            if (component instanceof Container container) result.addAll(descendants(container));
        }
        return result;
    }

    private static <T extends Component> T named(List<Component> components, String name, Class<T> type) {
        return components.stream()
                .filter(component -> name.equals(component.getName()))
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Named component missing: " + name));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
