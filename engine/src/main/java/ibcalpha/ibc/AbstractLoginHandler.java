// This file is part of IBC.
// Copyright (C) 2004 Steven M. Kearns (skearns23@yahoo.com )
// Copyright (C) 2004 - 2018 Richard L King (rlking@aultan.com)
// For conditions of distribution and use, see copyright notice in COPYING.txt

// IBC is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

// IBC is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.

// You should have received a copy of the GNU General Public License
// along with IBC.  If not, see <http://www.gnu.org/licenses/>.

// IBC Manager: generation-bound login clicks and GUI-thread-only challenge observation.
package ibcalpha.ibc;

import java.awt.Window;
import java.awt.event.WindowEvent;
import java.util.concurrent.TimeUnit;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;

public abstract class AbstractLoginHandler implements WindowHandler {

    @Override
    public boolean filterEvent(Window window, int eventId) {
        switch (eventId) {
            case WindowEvent.WINDOW_OPENED:
                Utils.logToConsole("Login dialog WINDOW_OPENED: LoginState is " + LoginManager.loginManager().getLoginState().toString());
                switch (LoginManager.loginManager().getLoginState()) {
                    case LOGGED_IN:
                        return false;
                    case LOGIN_FAILED:
                        return true;
                    case LOGGING_IN:
                        return false;
                    case TWO_FA_IN_PROGRESS:
                        // Register a reconstructed login frame after expiry, but
                        // handleWindow must leave resubmission to the existing deadline.
                        return true;
                    default:
                        return true;
                }
            default:
                return false;
        }
    }

    @Override
    public final void handleWindow(Window window, int eventID) {
        if (LoginManager.loginManager().getLoginHandler() == null) LoginManager.loginManager().setLoginHandler(this);
        LoginManager.loginManager().setLoginFrame((JFrame) window);
        switch (LoginManager.loginManager().getLoginState()){
            case LOGGED_OUT:
                if (! SessionManager.isRestart()) {
                    initiateLogin(window);
                } else {

                    // allow automatic relogin to ptoceed

                }
        }
    }

    @Override
    public abstract boolean recogniseWindow(Window window);
    
    private static int loginAttemptNumber = 0;
    int currentLoginAttemptNumber() {
        return loginAttemptNumber;
    }
    
    void initiateLogin(Window window) {
        LoginManager.loginManager().setLoginState(LoginManager.LoginState.AWAITING_CREDENTIALS);
        try {
            if (!initialise(window, WindowEvent.WINDOW_OPENED)) return;
            if (!setFields(window, WindowEvent.WINDOW_OPENED)) return;
            if (!preLogin(window, WindowEvent.WINDOW_OPENED)) return;

            Utils.logToConsole("Login attempt: " + ++loginAttemptNumber);
            doLogin(window);
        } catch (IbcException e) {
            Utils.exitWithError(ErrorCodes.CANT_FIND_CONTROL, "could not login: could not find control: " + e.getMessage());
        }
    }

    /** Called only after the retry adapter has restored and validated the exact form. */
    boolean initiatePreparedLogin(Window window) {
        if (StopTask.shutdownInProgress()) return false;
        LoginManager.loginManager().setLoginState(LoginManager.LoginState.AWAITING_CREDENTIALS);
        try {
            // Do not write the fields again: Gateway may asynchronously disable
            // its login button during document validation after each setText.
            if (!preLogin(window, WindowEvent.WINDOW_OPENED)) return false;
            Utils.logToConsole("Login attempt: " + ++loginAttemptNumber);
            doLogin(window);
            return true;
        } catch (IbcException failure) {
            Utils.logError("2FA retry login submission could not find a recognized control");
            return false;
        }
    }

    private void doLogin(final Window window) throws IbcException {
        
        final LoginManager owner = LoginManager.loginManager();
        final int attempt = currentLoginAttemptNumber();
        GuiDeferredExecutor.instance().execute(() -> {
            if (LoginManager.loginManager() != owner || StopTask.shutdownInProgress()
                    || currentLoginAttemptNumber() != attempt
                    || owner.getLoginState() != LoginManager.LoginState.AWAITING_CREDENTIALS
                    || !window.isShowing()) return;
            final JButton loginButton = findLoginButton(window);
            if (loginButton == null || !loginButton.isShowing() || !loginButton.isEnabled()) {
                Utils.logError("Login submission blocked: Log In control is unavailable");
                return;
            }
            LoginManager.loginManager().setLoginState(LoginManager.LoginState.LOGGING_IN);
            SwingUtils.clickButton(loginButton);
        });
        
        String tradingMode = TradingModeManager.tradingModeManager().getTradingMode();
        if (tradingMode.equalsIgnoreCase(TradingModeManager.TRADING_MODE_PAPER)) {
            // paper trading mode doesn't use Second Factor Authentication, so nothing
            // to do here
        } else {
            // Starting with TWS 1016, there is no longer a separate Second Factor
            // Authentication dialog. Instead, TWS replaces the Login frame's controls
            // with the controls that used to be in the 2FA dialog (so the Login frame
            // effectively becomes the 2FA frame). This doesn't generate any events
            // that IBC normally handles, so it goes undetected, and thus IBC doesn't
            // know when to process the Second Factor Authentication dialog. 
            //
            // Observe the actual challenge label, even when this Gateway version
            // did not show an initial literal "LOGIN" heading:
            // when this happens, we can pass the window to the SecondFactorAuthenticationDialogHandler
            // to be actioned.

            Utils.logToConsole("Waiting for Login frame to become SecondFactorAuthenticationDialog");
            MyScheduledExecutorService.getInstance().schedule(
                    () -> {
                        GuiDeferredExecutor.instance().execute(
                                () -> checkChangeToSecondFactorAuthenticationDialog(window, owner, attempt));
                    }, 
                    200, TimeUnit.MILLISECONDS);
        }
    }

    private void checkChangeToSecondFactorAuthenticationDialog(Window window,
            LoginManager owner, int attempt) {
        if (LoginManager.loginManager() != owner || currentLoginAttemptNumber() != attempt
                || StopTask.shutdownInProgress() || !window.isShowing()
                || (owner.getLoginState() != LoginManager.LoginState.LOGGING_IN
                && owner.getLoginState() != LoginManager.LoginState.TWO_FA_IN_PROGRESS)) return;
        if (hasVisibleSecondFactorHeading(window)) {
            // the login frame has now become the 2FA dialog, so invoke the 
            // handler for that as if it had just been opened
            Utils.logToConsole("Login frame has now become SecondFactorAuthenticationDialog");
            TwsListener.logWindow(window, WindowEvent.WINDOW_OPENED);
            TwsListener.logWindowStructure(window, WindowEvent.WINDOW_OPENED, true);
            SecondFactorAuthenticationDialogHandler.getInstance().handleWindow(window, WindowEvent.WINDOW_OPENED);
        } else {
            MyScheduledExecutorService.getInstance().schedule(
                    () -> {
                        GuiDeferredExecutor.instance().execute(
                                () -> checkChangeToSecondFactorAuthenticationDialog(window, owner, attempt));
                    }, 
                    200, TimeUnit.MILLISECONDS);
        }
    }
    
    static boolean hasVisibleSecondFactorHeading(Window window) {
        ComponentIterator components = new ComponentIterator(window);
        while (components.hasNext()) {
            java.awt.Component component = components.next();
            if (component instanceof JLabel label && label.isShowing() && label.getText() != null
                    && label.getText().toUpperCase(java.util.Locale.ROOT).contains("SECOND FACTOR AUTHENTICATION")) return true;
        }
        return false;
    }

    protected abstract boolean initialise(final Window window, int eventID) throws IbcException;

    protected abstract boolean preLogin(final Window window, int eventID) throws IbcException;

    protected abstract boolean setFields(Window window, int eventID) throws IbcException;
    
    protected abstract boolean isUserIdDisabledOrAbsent(Window window);

    protected abstract boolean isPasswordDisabledOrAbsent(Window window);

    private JButton findLoginButton(final Window window) {
        JButton b = SwingUtils.findButton(window, "Login");
        if (b == null) b = SwingUtils.findButton(window, "Log In");
        if (b == null) b = SwingUtils.findButton(window, "Paper Log In");
        return b;
    }

    protected final void setMissingCredential(final Window window, final int credentialIndex) {
        SwingUtils.findTextField(window, credentialIndex).requestFocus();
    }

    protected final void setCredential(final Window window, 
                                            final String credentialName,
                                            final int credentialIndex, 
                                            final String value) throws IbcException {
        if (! SwingUtils.setTextField(window, credentialIndex, value)) throw new IbcException(credentialName);
    }

    protected final boolean setTradingMode(final Window window) {
        String tradingMode = TradingModeManager.tradingModeManager().getTradingMode();

        if (SwingUtils.findToggleButton(window, "Live Trading") != null && 
                SwingUtils.findToggleButton(window, "Paper Trading") != null) {
            // TWS 974 onwards uses toggle buttons rather than a combo box
            Utils.logToConsole("Setting Trading mode = " + tradingMode);
            if (tradingMode.equalsIgnoreCase(TradingModeManager.TRADING_MODE_LIVE)) {
                SwingUtils.findToggleButton(window, "Live Trading").doClick();
            } else {
                SwingUtils.findToggleButton(window, "Paper Trading").doClick();
            }
            return true;
        } else {
            // the dialog appears to have been deconstructed, stop tidily
            // and do a cold restart
            
            Utils.logToConsole("Login dialog has been invalidated - initiate cold restart");
            MyCachedThreadPool.getInstance().execute(new StopTask(null, true, "Login Error dialog encountered"));
            return false;
        }
    }
    
}
