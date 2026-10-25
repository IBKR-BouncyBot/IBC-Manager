# IBC Manager 2.3.0

The October 25 incident exposed a gap in 2.2.0: Gateway displayed its expired-token
dialog during reconnect after a successful login. The earlier recovery accepted
only a logged-out restart and the title `Gateway`; the observed title was
`IBKR Gateway` and IBC still recorded the session as logged in.

Engine **3.24.2-manager.8** handles this established-session case while retaining
the existing pre-login recovery. It recognizes the exact expiry message, requests
one cold restart, reports the reason, and clears the previous session's readiness.
Established-session shutdown runs outside the Swing event thread. Its 30-second
graceful deadline has a fallback limited to the affected engine JVM. The dedicated
expiry marker makes the replacement start with fresh authentication. A same-session
pending guard prevents a replacement that has not successfully logged in from
falling back to stale warm data. Actual successful login clears the guard; existing
2FA-timeout and login-display-timeout retry policies remain separate and use the
cleared restart option.

**Normal warm restarts remain supported.** Ordinary reconnects, active login/2FA
waits, unrelated dialogs and `invalid twsInfo` rejections do not trigger this
recovery. Duplicate/stale requests and preceding Stop/PAUSE actions are guarded.
Once recovery commits, a later Stop retains the existing in-progress behavior;
explicit Force Stop remains separate. A canceled or unpreparable claimed recovery
does not rearm in that engine JVM and requires manual intervention.
Fresh authentication still requires usable credentials and any IB Key approval.
See [restart modes](RESTART_MODES.md) and [the fix evidence](ESTABLISHED_SESSION_EXPIRY_FIX.md).

The RDP headless workaround now pins both **JBR 25.0.4-b508.27** and **JavaFX
25.0.4**, with all eight required modules. Neither component follows newer releases
or falls back to another version when its download is unavailable. Matching managed
payloads are reused; a working installation with these exact versions does not
need reinstallation. A mismatched managed payload requires the existing explicit
remove/reinstall procedure. Manual runtime files remain untouched.

Manager's Windows runtime remains Microsoft Java 25; profile format remains **7**.
Stop profiles, back up the complete data directory, and extract 2.3.0 to a fresh
application folder under the same Windows account. See [upgrade instructions](UPGRADE_GUIDE.md).

The supplied 2.2.0 Windows validation and packaging logs reported 1,050 passing
cases / 27,105 assertions and passed GUI gates; four symbolic-link fixtures could
not run. Those results concern 2.2.0, not this change. The [current test report](../TEST_REPORT.md)
records newly executed 2.3.0 checks and outstanding Windows/native/live acceptance.
A Java/source package does not claim to include a native Windows installer.

Existing runtime ownership, licensing and upstream attribution are retained.
Preparing these release archives does not modify the live installation.
