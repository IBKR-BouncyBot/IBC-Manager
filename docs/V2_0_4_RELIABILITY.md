# 2.0.4: restart supervision, command dispatch and diagnostics

This is a targeted repair of four issues found during the Gateway 10.50 restart
investigation. It is based on the reuploaded 2.0.3 source, not a reconstruction from
1.x. Manager is 2.0.4, the maintained engine is 3.24.2-manager.4, and profile format
remains 6. The existing GNU GPL licences and attribution are retained.

## 1. One no-progress deadline across failed child replacements

StartIBC is a long-lived wrapper; it can replace its Gateway/engine JVM without
creating another Manager wrapper. Previously each replacement reset the five-minute
startup watchdog. A child failing to display the login window after 60 seconds could
therefore restart indefinitely without any individual attempt reaching five minutes.

The detector now retains the start of the unsuccessful episode and a child-launch
count. A new JVM without progress is another attempt, not a new deadline. An actual
Gateway launch milestone is still needed to arm the detector; mere wrapper startup
or a bootstrap logged-out event is not treated as a displayed login window.

Recognized login/2FA progress, completed login, or an owned API listener ends the
no-progress episode. A later legitimate restart gets a new budget. The detector does
not time out normal manual credential entry or approval waiting. It does not mistake
an unrelated listener for success. Explicit Stop/new wrapper creation resets local
detector bookkeeping; existing persistent recovery-history rules remain in effect.
The detector uses Manager receipt times and its existing Clock implementation. It
is not a new persistent incident database or an independent Windows watchdog.

At expiry, the existing bounded recovery policy applies: capture diagnostics,
revalidate the current child and listener evidence, request graceful STOP, and only
if still safe perform exact-process-tree cleanup, verify released ports, wait the
existing cooldown and launch one fresh wrapper. Automatic recovery must be enabled.
Disabled recovery reports STARTUP_STALLED without sending commands.

Safety boundaries are deliberately retained. A child that exits or is replaced while
cleanup is pending is not killed using a stale snapshot. If there is still no real
progress and the cumulative deadline is overdue, the attempt becomes RECOVERY_FAILED
and its pending-health record remains. It does not recursively queue another cleanup.
A genuine late login/2FA or recovered API cancels destructive escalation normally.

A fresh recovery that also fails is reported as RECOVERY_FAILED, including between
further wrapper retries. No additional force-restart task is allowed without health
or an explicit operator action; the existing two-recoveries-per-hour limit is not
increased. The surviving wrapper can still make its own retry attempts while that
failure is displayed. This release does not silently kill a replacement to stop those
attempts. Use the confirmed Force Stop/Start workflow if intervention remains needed.
Successful login/API progress can still resolve the pending recovery automatically.

## 2. GUI exports include the live runtime buffer

The GUI now uses the existing three-argument diagnostic export API and passes the
selected controller's current log snapshot. The snapshot is immutable and is redacted
and bounded by the existing diagnostic service. It includes Manager recovery messages
and recently observed engine output that may not yet be in the on-disk 60-second batch.

The regular runtime refresh still governs when child output reaches the controller
buffer. Export is a read-only snapshot, not a command, forced log flush, or API probe.
The two-argument service overload remains available for callers that truly have no live
buffer. A GUI regression executes the real export callback and verifies a buffer-only
sentinel in live-runtime-log.txt, plus redaction and absence from the disk tail.

## 3. Headless command dispatch no longer fails at class initialization

The inherited CommandDispatcher computed graphical shortcut modifiers in a static
initializer. With a headless Toolkit that threw before any command could be read,
including STOP and EXIT. Modifier lookup now happens only for reconnect commands.

A reconnect without a usable graphics environment receives an explicit ERROR response;
subsequent commands remain available. Generic command dispatch does not initialize
those shortcuts. STOP retains its existing behavior: a not-yet-logged-in engine shuts
down immediately, while a logged-in engine uses its existing graceful menu path.
This does not guarantee shutdown of a frozen JVM or make GUI-dependent operations
work without a desktop. No global key injection or indiscriminate process kill is added.

A fresh-JVM regression sends a real STOP over a temporary loopback command channel
and verifies its acknowledgement and zero process exit. The child is an isolated
pre-login test fixture, not Gateway and not an IBKR-connected session.

## 4. Startup stderr is captured and redacted before live or disk output

All four Java invocation branches in the maintained StartIBC.bat now use `2>&1`
instead of `2>NUL`. Their child exit status and existing restart decisions are retained.
This captures output written to the inherited standard-error stream; it cannot collect
messages that proprietary Gateway code redirects exclusively to another log file.

The detached relay now redacts complete bounded UTF-8 lines before both destinations:
its live output pipe and its sixty-second disk buffer. Inline error/VM-option secret
assignments are masked as well as the existing supported credential formats. Normal
stack frames and lifecycle event text remain available. CR, LF and CRLF are normalized
to logical LF lines; a final unterminated line is processed at EOF.

A line exceeding 64 Ki characters is omitted in full with a marker, rather than emitting
an unredacted prefix or tail. Existing live-queue and pending-byte limits remain. Final
process exit commits buffered redacted output. Losing the Manager UI's pipe does not
end the relay's ownership of its disk log and runtime-config cleanup.

Redaction remains best effort for recognized patterns, not a guarantee that arbitrary
third-party output contains no personal information. Review diagnostics before sharing.
This release does not read a live password into another service just to redact logs.

## Scope deliberately unchanged

The three existing authentication patches, five-minute 2FA deadline, GUI recognition,
credentials and profile migration, account/port safety, native startup-task behavior,
normal Stop/Restart/Pause confirmations, and passive API-listener monitoring remain.
NoMachine/RDP, the Gateway Java runtime, Windows locking, and display drivers are not
modified. This release repairs supervision and diagnostics; it does not supply the
Windows desktop required by Gateway or promise that an IBKR notification is delivered.

## Regression evidence

The source includes sanitized lifecycle-only fixtures from the October 10 failure
and October 11 successful restart. Full private diagnostic bundles are not redistributed.
The failure replay has ten child launches and nine 1112 exits. Against the unchanged
2.0.3 binary its detector never expires; against the repaired detector its first expiry
is at 300.424 seconds of virtual no-progress age. One-second replay polling explains
the fraction above 300 seconds; it is not a delayed real-phone test.

Headless dispatcher initialization and the actual GUI export regressions also fail
against the baseline and pass with the changes. The successful warm-restart fixture
stays non-stalled. Controller tests cover disabled recovery, repeated fresh failure,
late authentication, replacement/exited children, and unknown/unrelated API listeners.
Output tests cover fragmented Unicode, line endings, limits, redaction, delayed writes,
EOF and preservation of a nonzero child exit code. Windows-only CMD branches must run
on Windows; Linux static checks are not represented as native execution.
