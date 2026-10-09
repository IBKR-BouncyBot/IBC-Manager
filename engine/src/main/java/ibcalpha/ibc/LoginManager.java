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

// IBC Manager integration: lifecycle events and cancellable 2FA challenge deadlines.
package ibcalpha.ibc;

import java.awt.Window;
import javax.swing.JFrame;

public abstract class LoginManager {

    private static LoginManager _LoginManager;

    static {
        _LoginManager = new DefaultLoginManager();
    }

    public static void initialise(LoginManager loginManager){
        if (loginManager == null) throw new IllegalArgumentException("loginManager");
        _LoginManager.cancelSecondFactorRetry();
        _LoginManager = loginManager;
    }

    public static void setDefault() {
        initialise(new DefaultLoginManager());
    }

    public static LoginManager loginManager() {
        return _LoginManager;
    }

    public enum LoginState{
        LOGGED_OUT,
        LOGGED_IN,
        LOGGING_IN,
        TWO_FA_IN_PROGRESS,
        LOGIN_FAILED,
        AWAITING_CREDENTIALS
    }

    boolean readonlyLoginRequired() {
        boolean readOnly = Settings.settings().getBoolean("ReadOnlyLogin", false);
        if (readOnly && SessionManager.isGateway()) {
            Utils.logError("Read-only login not supported by Gateway");
            return false;
        }
        return readOnly;
    }
    
    private volatile JFrame loginFrame = null;
    JFrame getLoginFrame() {
        return loginFrame;
    }

    void setLoginFrame(JFrame window) {
        loginFrame = window;
    }
    
    private volatile LoginState loginState = LoginState.LOGGED_OUT;
    public LoginState getLoginState() {
        return loginState;
    }

    public synchronized void setLoginState(LoginState state) {
        if (state == loginState) return;
        loginState = state;
        if (state != LoginState.TWO_FA_IN_PROGRESS) cancelSecondFactorRetry();
        if (state != null) EngineEvents.emit("LOGIN_" + state.name());
        if (null != loginState) switch (loginState) {
            case TWO_FA_IN_PROGRESS:
                Utils.logToConsole("Second Factor Authentication initiated");
                break;
            case LOGGED_IN:
                Utils.logToConsole("Login has completed");
                break;
            default:
                break;
        }
    }

    private SecondFactorRetry secondFactorRetry;
    private Window secondFactorWindow;
    private boolean secondFactorWindowClosed;
    private long secondFactorGeneration;

    synchronized void secondFactorAuthenticationDialogOpened(Window window) {
        if (loginState == LoginState.LOGGED_IN || StopTask.shutdownInProgress()) return;
        setLoginState(LoginState.TWO_FA_IN_PROGRESS);
        if (!reloginPermitted() || StopTask.shutdownInProgress()) return;
        if (secondFactorRetry != null && secondFactorWindow == window && !secondFactorWindowClosed) return;
        cancelSecondFactorRetry();
        secondFactorWindow = window;
        secondFactorWindowClosed = false;
        // The deadline starts at the actual challenge, not at credential submission.
        secondFactorRetry = new SecondFactorRetry(new SecondFactorRetryTarget(this, window, secondFactorGeneration),
                Settings.settings().getInt("SecondFactorAuthenticationTimeout", 300));
        secondFactorRetry.start();
    }

    synchronized void cancelSecondFactorRetry() {
        secondFactorGeneration++;
        if (secondFactorRetry != null) secondFactorRetry.close();
        secondFactorRetry = null;
        secondFactorWindow = null;
        secondFactorWindowClosed = false;
    }

    synchronized boolean isCurrentSecondFactorChallenge(Window window, long generation) {
        return secondFactorWindow == window && secondFactorGeneration == generation;
    }

    synchronized void secondFactorAuthenticationDialogClosed(Window window) {
        if (window == secondFactorWindow) secondFactorWindowClosed = true;
        // A close can be approval, cancellation or expiry. None of those alone
        // proves login success or authorizes a second scheduler/process exit.
        // Keep the original deadline until success or a new login generation.
    }

    boolean reloginPermitted() {
        if (Settings.settings().getString("ReloginAfterSecondFactorAuthenticationTimeout", "").isEmpty()) {
            if (!Settings.settings().getString("ExitAfterSecondFactorAuthenticationTimeout", "").isEmpty()) {
                return Settings.settings().getBoolean("ExitAfterSecondFactorAuthenticationTimeout", false);
            }
            return false;
        }
        return Settings.settings().getBoolean("ReloginAfterSecondFactorAuthenticationTimeout", false);
    }
    
    public abstract void logDiagnosticMessage();

    public abstract String FIXPassword();

    public abstract String FIXUserName();

    public abstract String IBAPIPassword();

    public abstract String IBAPIUserName();

    public abstract AbstractLoginHandler getLoginHandler();

    public abstract void setLoginHandler(AbstractLoginHandler handler);

}
