# Testing

Manager and maintained engine sources are compiled by the same JDK-native build
with `--release 17 -encoding UTF-8 -g -Xlint:all -Werror`. No legacy warning
categories are disabled. Source-build output and manifests are deterministic on
the same toolchain; this is not a promise that different javac versions produce
identical class bytes.

`test.bat` runs the full headless suite. `validate-windows.bat` additionally runs
the real Manager GUI smoke , `ibcalpha.ibc.EngineGuiSmoke` and `ibcalpha.ibc.SecondFactorRetryGuiSmoke`
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
