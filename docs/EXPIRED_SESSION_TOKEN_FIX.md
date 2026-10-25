# Expired restart-token recovery — engine 3.24.2-manager.7

Historical 2.2.0 implementation and evidence. The 2.3.0 / manager.8 extension for
expiry after completed login, `IBKR Gateway` title recognition and dedicated
wrapper recovery is documented in
[ESTABLISHED_SESSION_EXPIRY_FIX.md](ESTABLISHED_SESSION_EXPIRY_FIX.md).

This fix is included in IBC Manager 2.2.0, following its initial verification on
2.1.0 source. The installed application,
working JBR 25.0.4-b508.27 / JavaFX 25.0.4, profile schema, credentials and schedules
have not been changed. No broker login or production operation was performed.

## Verified cause and actual control

The 18 October diagnostic records an expired security token, then a dialog titled
`Gateway`. The retained IBC 3.24.2 handler reads only `JLabel` text and extracts
`GATEWAY`; it does not reach its `Connection to server failed` recovery condition.

Static inspection of the previously supplied Gateway 10.50.1e binaries now
establishes the body control. In `jts4launch-1050.jar`:

- `ibgateway.af.a(String, Integer, ..., int, AuthProcessState)` passes the
  authorization-failure message to `ibgateway.N`.
- `ibgateway.N.run()` prefixes `Connection to server failed: ` and invokes
  `ibgateway.aw.b(String)`.
- `aw.b(String)`, bytecode offsets 52–76, creates a `JTextPane`, installs
  `HTMLEditorKit`, and calls `setEditable(false)`. Offsets 97–104 insert the
  processed error; 122–134 create the `JOptionPane` dialog. The incident's title
  is the `Gateway` title branch at offsets 45–50.
- `aw.d(String)` and inherited `twslaunch.jutils.ax.o(String)` normalize the
  outer HTML wrappers. The full message remains in the noneditable document.

Input SHA-256:
- Manager source ZIP:
  `ec75f1fe128f89b649c41de5d274edde3d25ab8bb640ede6078f3f5484496077`
- Gateway `jars.zip`:
  `9e2cf87d790dbfc384170f416be1d805d4b69430e4ece173742188cbd0588176`
- `jts4launch-1050.jar`:
  `e635ccb623a15b025c44effdb37834a42cc1fdded7a1ee3b5860ba5c9d4ed4b8`
- `ibgateway/aw.class`:
  `3487a4d23d9a6d0897a6070055ac138f55766aae91b055f90fcab9200d57558a`

This establishes component construction and message flow, not a live recovery
result. No proprietary Gateway binaries are added to the source or product.
The class hashes, bytecode offsets and reproduction command are retained in
[`validation/GATEWAY_EXPIRY_BINARY_EVIDENCE.txt`](validation/GATEWAY_EXPIRY_BINARY_EVIDENCE.txt).

## Implementation

`ExpiredSessionTokenDialogHandler` is registered immediately after the existing
invalid-twsInfo handler and before generic handlers. It requires:

- A displayed, displayable `Gateway` JDialog and visible enabled `OK` button.
- Both the known expired-security-token phrase and instruction to manually enter
  username/password to access IBKR Gateway.
- Gateway mode, an active `-Drestart` attempt, `LOGGED_OUT`, and no shutdown.

Extraction is bounded to 512 components and 4,096 characters per display control.
Hidden branches, editable text and password fields are excluded. HTML is normalized
using Swing's parser; the actual `JTextPane` display document is read directly,
without serializing its formatting or following hyperlinks.

The handler claims one recovery for its lifetime before queueing work. On the EDT
it rechecks the same LoginManager, a monotonic login-state generation, eligibility
and the still-visible message before acting. This also cancels an action if login
progressed and subsequently returned to `LOGGED_OUT`.

The action emits the fixed `LOGIN_RESTART_TOKEN_EXPIRED` event and invokes
`StopTask(null, true, ...)`. The existing cold-restart marker and pre-login exit
path are reused. A null-channel guard protects the existing duplicate-STOP branch
when a command-thread STOP wins the shutdown claim.

The Manager parser validates event generation/sequence, reports the expiry reason
as `RESTARTING`, retains it through old-child shutdown messages, and resets it at
the actual replacement launch. The event requests recovery; it does not assert
that shutdown or replacement login has completed. Invalid-twsInfo remains
authoritative.

General `WAITING_FOR_LOGIN` / 2FA watchdog exemptions are unchanged. No general
login timeout, log-file watcher, new setting, forced runtime change or profile
migration is introduced.

## Restart scope and compatibility

Once the wrapper takes its existing cold-restart branch, it clears
`AUTORESTART_OPTION`. The resulting fresh child is ineligible for this handler,
so it cannot request this same token-recovery action again. Credentials and IB Key
approval then follow the existing full-login path.

The Windows launcher is unchanged. Its existing descriptor lookup precedes its
cold-restart-marker branch. The recorded incident explicitly shows Gateway consuming
and deleting the expired descriptor at launch. This patch does not claim to resolve
every possible stray-descriptor or unrelated wrapper-restart condition.

`ColdRestartTime` remains optional; daily restart, PAUSE, TWS, unknown dialogs,
normal 2FA waits and the dedicated invalid-twsInfo path retain their existing rules.
The engine revision changes to `3.24.2-manager.7`, ensuring a separate verified
engine payload/cache. The release application version is 2.2.0; profile format 7
and the pinned runtime configuration remain unchanged.

The seventh Java patch reproduces the maintained engine from the retained
upstream snapshot plus earlier patches. The upstream snapshot and both existing
Windows resource patches remain unchanged.

## Regression coverage

The new headless suite exercises the recorded HTML and plain text, the binary-
verified JTextPane/HTMLEditorKit control, HTML whitespace/entities, old prefix,
hidden/editable/password controls, bounds, partial/unrelated messages, duplicate
events/candidates, provider/generation/state changes and delayed action cancellation.

A forked JVM uses the production eligibility checks and actual StopTask, proves
fresh-start/TWS/active-login exclusions, creates only a temporary COLDRESTART marker,
and exits through the real pre-login path. It neither loads Gateway nor opens a
command/API listening socket.

New Manager tests replay the incident's event pattern and check current/stale/
duplicate generations, reason retention, replacement login/2FA, and invalid-twsInfo
precedence. The existing startup-watchdog behavior remains covered.

`ExpiredSessionTokenGuiSmoke` is included in the normal `gui-smoke` build gate.
It uses real JDialogs/JOptionPane controls, handler registration and window events.
It is a fixture test; it does not authenticate against IBKR.

## Initial fix validation (before the 2.2.0 version update)

Fresh 2.2.0 results and release packaging limitations are recorded in
[`../TEST_REPORT.md`](../TEST_REPORT.md). The following is the retained initial
fix comparison against the unmodified 2.1.0 source.

Executed on 18 October 2026 with Java 17 in the Linux analysis container:

| Check | Result |
| --- | --- |
| Build-driver self-test | Passed |
| Strict production and test compilation | Passed; 90 engine and 126 Manager production Java files |
| New Manager event regressions | 13 passed |
| New engine headless regressions | 23 passed, including real StopTask marker/exit in a forked JVM |
| Full patched suite | 1,050 cases, 27,031 assertions; 1,040 passed, 10 failed |
| Unmodified baseline suite | 1,014 cases, 26,783 assertions; 1,004 passed, the same 10 failed |
| Failure comparison | All ten failure lines match the baseline exactly; no additional failures |
| JAR build and headless command-line smoke | Passed |
| Maintained-engine patch reproduction | All 89 upstream hashes match; seven Java patches reproduce all 90 maintained Java files byte for byte |
| Existing Windows launcher resources | Byte-identical to the original source |
| GUI smoke | Compiled; not executed without a display |
| Windows and live Gateway acceptance | Not executed |

The exact commands were:

```text
# Unmodified source, separate directory:
java src/build/java/io/github/ibcmanager/build/BuildProject.java test
# Patched source:
java src/build/java/io/github/ibcmanager/build/BuildProject.java self-test clean test
java src/build/java/io/github/ibcmanager/build/BuildProject.java jar smoke
```

The full test command exits unsuccessfully because of the ten baseline failures.
No test was removed, skipped or relaxed to obtain the comparison. The repeated
failures are:

- process tree terminator kills the exact root and descendants
- process identity store round-trips identity
- process identity store reattaches only matching start time
- controller reattaches to an exact live process identity
- reattached Force Stop tolerates a delayed parent Process exit observation
- reattachment exit verification rejects a still-live child within its deadline
- reattachment exit verification propagates an interrupted wait
- live legacy process prevents migration without changing configuration
- live legacy process blocks configuration import
- process-handle identity matches the current process

These are existing process-tree/identity failures reproduced in this container;
the comparison does not establish that they would pass on another platform.
The initial compiled JAR was used only for the local smoke check. The subsequent
2.2.0 Java release includes the versioned application JAR and corresponding
source; it does not include a native Windows installer or change the runtime pin.

## Required before deployment or release

Run the complete build and GUI gates on Windows using `validate-windows.bat`.
Then verify a paper Gateway 10.50.1e token-expiry recovery reaches fresh credentials
and a real IB Key challenge, followed by completed login and the correct owned API
listener. Check ordinary scheduled restart, PAUSE and subsequent daily restarts.

The current environment has Java 17 with the compiler module but no X display
server. Attempts to obtain Xvfb from the configured repository returned HTTP 403.
Top-level GUI execution, native Windows command-script execution and live Gateway
authentication therefore remain explicit acceptance gates.

