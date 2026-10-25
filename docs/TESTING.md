# Testing

Manager and maintained engine sources are compiled by the same JDK-native build
with `--release 17 -encoding UTF-8 -g -Xlint:all -Werror`. No legacy warning
categories are disabled. Source-build output and manifests are deterministic on
the same toolchain; this is not a promise that different javac versions produce
identical class bytes.

`test.bat` runs the full headless suite. `validate-windows.bat` additionally runs
the real Manager GUI smoke, `ibcalpha.ibc.EngineGuiSmoke`,
`ibcalpha.ibc.ExpiredSessionTokenGuiSmoke` and `ibcalpha.ibc.SecondFactorRetryGuiSmoke`
in separate JVMs.
The engine contract suite executes actual maintained IBC classes. Fresh JVM
fixtures isolate static state and prove lifecycle output survives Gateway-style
System.out replacement. Randomized configurations are read by actual
DefaultSettings, not only round-tripped through Manager's own parser.

The engine GUI runner uses actual Swing widgets and handlers without proprietary
Gateway binaries. Its four scenarios cover main-window detection, incoming API
connection prompts, blind-trading warning policy and the real command dispatcher
before main-window availability. It verifies fail-closed reconnect, rejected
Gateway ENABLEAPI, invalid commands and EXIT over a real socket pair.

Source integration, default/custom in-place upgrades, disabled legacy TWS,
credential identity preservation, original backups, corrupted configuration
rollback, interrupted transactions, live-process refusal and startup lock cleanup
have regression tests. Existing runtime, recovery, passive listener, secret
redaction, config sync and native packaging-structure tests remain.

The Linux build host is a test harness, not a supported product environment.
OS-conditional tests do not substitute for Windows DPAPI/ACL/Task Scheduler or
actual Gateway authentication. Read TEST_REPORT.md and the Windows checklist.
The native EXE, installer and genuine `_windows.zip` must be built on Windows.

## Deadline coverage in 2.0.2

Seventeen virtual-time tests exercise the actual SecondFactorRetry state machine:
300-second boundary, open and early-closed challenges, duplicate starts, queued
cancellation, replacement attempts, repeated deadlines, missing credentials,
unknown/unresponsive controls, late success, shutdown/failure, monotonic wrap,
early scheduler wake-up and bounded safe exception handling.

The actual-engine GUI runner covers 23 scenarios with real Swing timers and
controls (one-second setting for test speed). It exercises modal and transformed
login-frame challenges and the actual GatewayLoginFrameHandler. The optional
`--real-five-minutes` argument runs one scenario with the real 300-second setting.
A fixture is not a live IBKR authentication request. Never interpret an in-process
retry event as evidence that a phone notification was delivered.

Five returned-login regressions add cleared password/disabled button, registered
replacement frame, missing initial LOGIN heading, asynchronous credential validation
and a hidden retired inline challenge heading. The real-duration path uses cleared
credentials and asynchronous validation. The Profile-only suite tests migration,
missing sparse values, rollback, one authority and editor/save guards.


## Reattached-process exit observation

The controller reattachment case verifies the native handle is stopped, then uses
a bounded waitFor on its independently held Process before checking final state.
Do not replace this with an immediate Process.isAlive assertion or a fixed sleep.
The deterministic delayed-observer regression must remain enabled, as must the
still-live-child, already-exited and interrupted-wait controls. A live native handle
on Force Stop return, a timed-out wait, or an unrelated killed process must fail.
See REATTACH_TEST_FIX.md for the reported Windows failure and local reproduction.

## 2.3.0 established-session expiry and runtime pins

The engine headless suite exercises exact dialog recognition, startup and
established-session eligibility, deferred stale-action rejection, Stop/Pause
arbitration, actual marker preparation, bounded termination in isolated child
JVMs, marker-loss cancellation, pending recovery refusal and successful-login
guard cleanup. Manager tests cover current/stale event generations, readiness
revocation, error retention, invalid-twsInfo precedence and actual-child reset.

`ExpiredSessionTokenGuiSmoke` has ten scenarios. Its three new production-engine
child modes cover originally warm and originally fresh logged-in sessions,
nested modal dismissal, real Relogin suppression, graceful File / Close and
disabled-Close deadline termination. These require a usable display.

`TokenExpiryWrapperTests` extracts the maintained Windows exit dispatcher and
executes it under native CMD with inert launch/end labels, without Gateway. Its
15 native scenarios check fresh startup, nonzero child exit, ordinary warm/cold/
Pause/stop behavior, malformed and conflicting markers, failed-fresh guards,
existing 2FA/display retries, and successful acknowledgment. Linux logs explicitly
report these native checks as unavailable; the enclosing named tests returning
success is not evidence that CMD executed. Run `validate-windows.bat` to execute
them together with the GUI and platform tests.

Runtime fixtures test fixed URLs with no moving catalogue lookup, exact JBR
identity, all required JavaFX modules at 25.0.4, matching offline reuse, mismatch
rejection, and retained safe removal of older owned payloads. Fixtures are not
live publisher downloads, RDP sessions or IBKR authentication. Current executed
results and outstanding gates are recorded in [TEST_REPORT.md](../TEST_REPORT.md).
