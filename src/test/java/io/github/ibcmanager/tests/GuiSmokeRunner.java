package io.github.ibcmanager.tests;

import io.github.ibcmanager.app.AppPaths;
import io.github.ibcmanager.app.AppServices;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.ui.MainFrame;
import io.github.ibcmanager.ui.ProfileEditorDialog;
import io.github.ibcmanager.ui.UiTheme;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JList;
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
                        require(frame.getTitle().startsWith("IBC Manager 1.0.13"), "window title is incorrect");
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
                        require(forceStopPresent, "force-stop recovery action is missing from Tools menu");
                        List<Component> components = descendants(frame);
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
                                        && statusIndicator.getAccessibleContext().getAccessibleDescription().contains("Stopped"),
                                "profile status indicator lacks explicit state text");
                        JButton enableApi = components.stream().filter(JButton.class::isInstance)
                                .map(JButton.class::cast)
                                .filter(button -> "Enable API".equals(button.getText()))
                                .findFirst().orElseThrow();
                        require(!enableApi.isEnabled(), "Enable API must stay disabled for an IB Gateway profile");
                        require(enableApi.getToolTipText() != null
                                        && enableApi.getToolTipText().contains("not IB Gateway"),
                                "Gateway-specific ENABLEAPI limitation must be explained");
                        JButton start = named(components, "startProfileButton", JButton.class);
                        JButton stop = named(components, "stopProfileButton", JButton.class);
                        JButton restart = named(components, "restartProfileButton", JButton.class);
                        JButton pause = named(components, "pauseProfileButton", JButton.class);
                        for (JButton action : List.of(start, stop, restart, pause)) {
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
                        List<JButton> actions = List.of(start, stop, restart, pause);
                        for (int first = 0; first < actions.size(); first++) {
                            for (int second = first + 1; second < actions.size(); second++) {
                                require(!actions.get(first).getForeground().equals(actions.get(second).getForeground()),
                                        actions.get(first).getText() + " and " + actions.get(second).getText()
                                                + " buttons are not visually distinct");
                            }
                        }

                        JDialog profileDialog = profileDialogRef.get();
                        require(profileDialog != null && profileDialog.isShowing(),
                                "profile editor was not created and displayed");
                        List<Component> profileComponents = descendants(profileDialog);
                        JButton detect = named(profileComponents, "detectInstallationsButton", JButton.class);
                        JButton install = named(profileComponents, "installIbcButton", JButton.class);
                        JTextField secondFactor = named(profileComponents, "secondFactorDeviceField", JTextField.class);
                        require(detect.getWidth() >= detect.getPreferredSize().width,
                                "detect-installations button was compressed horizontally");
                        require(detect.getHeight() >= detect.getPreferredSize().height,
                                "detect-installations button was compressed vertically");
                        require(install.getWidth() >= install.getPreferredSize().width,
                                "IBC install button was compressed horizontally");
                        require(install.getHeight() >= install.getPreferredSize().height,
                                "IBC install button was compressed vertically");
                        require(secondFactor.isVisible(), "SecondFactorDevice field is not visible on the profile tab");
                        require(profileComponents.stream().anyMatch(JScrollPane.class::isInstance),
                                "profile editor must provide scrolling instead of compressing controls");
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

                        frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
                        require(!frame.isDisplayable(), "normal close path did not dispose the window");
                    } catch (Throwable throwable) {
                        failure.set(throwable);
                    }
                });
                if (failure.get() != null) throw new AssertionError("GUI smoke assertion failed", failure.get());
            }
            System.out.println("GUI SMOKE PASSED");
        } finally {
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
