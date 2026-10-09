// IBC Manager maintained engine; GPL-3.0-or-later.
package ibcalpha.ibc;

import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.JTextField;
import javax.swing.JToggleButton;

/** Only operates on the exact challenge and a login frame registered by this engine. */
final class SecondFactorRetryTarget implements SecondFactorRetry.Target {
    private final LoginManager owner;
    private final Window challenge;
    private final JFrame originalFrame;
    private JFrame preparedFrame;
    private JTextField preparedUser;
    private JTextField preparedPassword;
    private final AbstractLoginHandler handler;
    private final long generation;

    SecondFactorRetryTarget(LoginManager owner, Window challenge, long generation) {
        this.owner = owner;
        this.generation = generation;
        this.challenge = challenge;
        this.originalFrame = owner.getLoginFrame();
        this.handler = owner.getLoginHandler();
    }

    @Override public boolean isPending() {
        requireEdt();
        return LoginManager.loginManager() == owner && !StopTask.shutdownInProgress()
                && owner.isCurrentSecondFactorChallenge(challenge, generation)
                && owner.getLoginState() == LoginManager.LoginState.TWO_FA_IN_PROGRESS
                && owner.reloginPermitted()
                && owner.getLoginHandler() == handler;
    }

    @Override public boolean hasCredentials() {
        requireEdt();
        return originalFrame != null && handler != null
                && owner.getLoginFrame() != null && owner.getLoginFrame().isDisplayable()
                && !owner.IBAPIUserName().isEmpty() && !owner.IBAPIPassword().isEmpty();
    }

    @Override public boolean isChallengeShowing() {
        requireEdt();
        if (!challenge.isShowing()) return false;
        if (challenge != originalFrame) return true;
        // Some Gateway generations replace the controls inside the login frame.
        return AbstractLoginHandler.hasVisibleSecondFactorHeading(challenge)
                || handler == null || !handler.recogniseWindow(challenge);
    }

    @Override public boolean cancelChallenge() {
        requireEdt();
        if (!isPending() || hasOtherModalWindow(challenge)) return false;
        // Do not dispose a window or synthesize a close event: only the app's own
        // unambiguous, visible Cancel action may cancel an active challenge.
        List<JButton> candidates = new ArrayList<>();
        collectCancel(challenge, candidates);
        if (candidates.size() != 1) return false;
        candidates.get(0).doClick(0);
        return true;
    }

    @Override public void prepareLogin() {
        requireEdt();
        if (!isPending() || isChallengeShowing()) return;
        JFrame frame = owner.getLoginFrame();
        if (!isLoginFormUsable(frame, false)) return;
        JTextField user = SwingUtils.findTextField(frame, 0);
        JTextField password = SwingUtils.findTextField(frame, 1);
        if (preparedFrame == frame && preparedUser == user && preparedPassword == password) return;
        // A returned form commonly clears its password and disables Log In until
        // credentials have been restored. Waiting for an enabled button first
        // deadlocks that UI. Populate once, then wait for the real button to enable.
        try {
            if (!handler.initialise(frame, java.awt.event.WindowEvent.WINDOW_OPENED)) return;
            if (!isPending() || !isLoginFormUsable(frame, false)) return;
            user = SwingUtils.findTextField(frame, 0);
            password = SwingUtils.findTextField(frame, 1);
            if (!handler.setFields(frame, java.awt.event.WindowEvent.WINDOW_OPENED)) return;
            preparedFrame = frame;
            preparedUser = user;
            preparedPassword = password;
        } catch (IbcException failure) {
            // No guessed field, arbitrary exception text or fatal engine exit.
            Utils.logError("2FA retry: recognized login fields could not be populated");
        }
    }

    @Override public boolean isLoginReady() {
        requireEdt();
        JFrame frame = owner.getLoginFrame();
        return preparedFrame == frame && frame != null
                && preparedUser == SwingUtils.findTextField(frame, 0)
                && preparedPassword == SwingUtils.findTextField(frame, 1)
                && isLoginFormUsable(frame, true);
    }

    private boolean isLoginFormUsable(JFrame frame, boolean requireEnabledButton) {
        if (frame == null || handler == null || !frame.isShowing() || !frame.isEnabled()
                || !handler.recogniseWindow(frame) || hasOtherModalWindow(null)) return false;
        // Only a form registered by this engine's login handler is eligible. If
        // another recognized login frame is showing, do not choose between them.
        for (Window window : Window.getWindows()) {
            if (window != frame && window.isShowing() && handler.recogniseWindow(window)) return false;
        }
        JTextField user = SwingUtils.findTextField(frame, 0);
        JTextField password = SwingUtils.findTextField(frame, 1);
        if (user == null || password == null || !user.isShowing() || !password.isShowing()
                || !user.isEnabled() || !password.isEnabled()
                || !user.isEditable() || !password.isEditable()) return false;
        return usableGatewaySelectors(frame) && existingLoginButtonIsUsable(frame, requireEnabledButton)
                && visibleLoginButtons(frame) == 1;
    }

    private boolean usableGatewaySelectors(JFrame frame) {
        JToggleButton api = SwingUtils.findToggleButton(frame, "IB API");
        if (api == null) api = SwingUtils.findToggleButton(frame, "TWS/API");
        if (api == null || !api.isShowing() || !api.isEnabled() || !api.isSelected()) return false;
        if (SwingUtils.findLabel(frame, "Trading Mode") != null) {
            JToggleButton live = SwingUtils.findToggleButton(frame, "Live Trading");
            JToggleButton paper = SwingUtils.findToggleButton(frame, "Paper Trading");
            if (live == null || paper == null || !live.isShowing() || !paper.isShowing()
                    || !live.isEnabled() || !paper.isEnabled()) return false;
        }
        return true;
    }

    private boolean existingLoginButtonIsUsable(JFrame frame, boolean requireEnabled) {
        // Mirror the actual handler's selector order, including hidden aliases.
        JButton button = SwingUtils.findButton(frame, "Login");
        if (button == null) button = SwingUtils.findButton(frame, "Log In");
        if (button == null) button = SwingUtils.findButton(frame, "Paper Log In");
        return button != null && button.isShowing() && (!requireEnabled || button.isEnabled());
    }

    @Override public boolean retryLogin() {
        requireEdt();
        // The deadline state machine rechecks immediately before calling here.
        if (!isPending() || !isLoginReady()) return false;
        return handler.initiatePreparedLogin(owner.getLoginFrame());
    }

    @Override public void event(String event) {
        requireEdt();
        EngineEvents.emit(event);
        switch (event) {
            case "SECOND_FACTOR_RETRY_ARMED" -> Utils.logToConsole(
                    "2FA retry deadline armed for "
                    + Settings.settings().getInt("SecondFactorAuthenticationTimeout", 300) + " seconds");
            case "SECOND_FACTOR_RETRY_DUE" -> Utils.logToConsole(
                    "2FA retry deadline reached; requesting an in-process relogin");
            case "SECOND_FACTOR_RETRY_STARTED" -> Utils.logToConsole(
                    "2FA relogin initiated; phone notification delivery is controlled by IBKR");
            case "SECOND_FACTOR_RETRY_BLOCKED" -> Utils.logError(
                    "2FA retry blocked: " + blockedReason() + "; no process was killed");
            default -> throw new IllegalArgumentException("Unknown retry event");
        }
    }

    private String blockedReason() {
        if (!hasCredentials()) return "stored credentials or registered login frame unavailable";
        if (hasOtherModalWindow(challenge)) return "another modal dialog is open";
        if (isChallengeShowing()) return "challenge remains visible or Cancel is missing/ambiguous";
        JFrame frame = owner.getLoginFrame();
        if (frame == null || !frame.isShowing()) return "registered login frame is not visible";
        if (!isLoginFormUsable(frame, false)) return "login form or API selector is unavailable/ambiguous";
        if (!existingLoginButtonIsUsable(frame, true)) return "login button remained disabled after credential restoration";
        return "login attempt changed before submission";
    }

    private static void collectCancel(Component component, List<JButton> buttons) {
        if (component instanceof JButton button && button.isShowing() && button.isEnabled()
                && "Cancel".equals(button.getText())) buttons.add(button);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) collectCancel(child, buttons);
        }
    }

    private static int visibleLoginButtons(Component component) {
        int count = 0;
        if (component instanceof JButton button && button.isShowing()
                && ("Login".equals(button.getText()) || "Log In".equals(button.getText())
                || "Paper Log In".equals(button.getText()))) count++;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) count += visibleLoginButtons(child);
        }
        return count;
    }

    private static boolean hasOtherModalWindow(Window permitted) {
        // This enumerates only this Gateway JVM's Swing windows, not the desktop
        // or other profiles. Never click behind a competing modal message.
        for (Window window : Window.getWindows()) {
            if (window != permitted && window instanceof JDialog dialog
                    && dialog.isModal() && dialog.isShowing()) return true;
        }
        return false;
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("2FA UI action outside EDT");
    }
}
