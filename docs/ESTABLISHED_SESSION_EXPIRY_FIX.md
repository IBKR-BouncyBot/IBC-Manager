# Established-session token expiry — IBC Manager 2.3.0

Application version: 2.3.0. Engine revision: 3.24.2-manager.8.
The release also pins the RDP workaround to JBR 25.0.4-b508.27 and JavaFX 25.0.4.
The live installation was not modified during development.

## Evidence and defect

Input: `IBC-Manager-Diagnostics-IB-Gateway-1050-20261025-081539.zip`, SHA-256
`0fb53403c0cf0648b8c8db5761581ea385bc2bc8a33338b058a13c914d409bdf`, and the
25 October screenshot. The archive identifies Manager 2.2.0 / engine manager.7.

The recorded sequence, in Europe/Amsterdam local time, is:

| Time | Verified event |
| --- | --- |
| 24 October 19:47:22 | Warm restart completed login. |
| 25 October 06:25:47 | The same Gateway child started reconnecting. |
| 06:29:44 | Dialog titled `IBKR Gateway` appeared; screenshot shows explicit token expiry. |
| 09:15:12 | Existing IBC relogin handler clicked Re-login; username/password error followed. |
| 09:15:25 | Wrapper exited normally without a cold-restart request. |

The former handler accepted only `Gateway` and required `LOGGED_OUT`. This child
still had `LOGGED_IN` state. Manager also rejected its old startup-expiry event
after completed login. These were design and coverage gaps; the passing 2.2.0
Windows fixtures did not exercise this established-session sequence.

The screenshot proves visible message text and title. The current post-login
message control class is not identified by the diagnostics: structure logging
was off. The implementation handles bounded displayed labels and noneditable
Swing text documents; tests cover multiple supported display controls. This is
not a claim that the exact proprietary post-login component tree was captured.

The readable launcher logs end after the preceding successful login. The new
encrypted application log is inventoried but excluded; no plaintext upstream
authentication payload is available. The later username/password dialog does
not prove that the stored credentials were wrong. No current invalid-twsInfo
rejection is shown. Gateway retained JBR 25.0.4-b508.27 / JavaFX 25.0.4; the
manifest's Java 25.0.4.1 belongs to Manager's separate JVM.

## Recovery behavior

The expiry handler requires the exact known expiry and manual-login phrases,
an enabled visible OK button, and a displayed Gateway dialog with title
`Gateway` or `IBKR Gateway`. It excludes editable/password controls and retains
bounded component/text extraction. General disconnects and calendar time do
not authorize a restart.

Two cases are supported:

- A logged-out warm-restart child retains the existing startup-expiry event.
- An already logged-in Gateway child emits `SESSION_TOKEN_EXPIRED`, regardless
  of whether that child originally used a warm restart or full authentication.

The queued action rechecks the login-manager identity, state generation and
still-visible message. It claims recovery only once per handler. A preceding
Stop or Pause/Restart claim prevents automatic expiry recovery. Active login
and 2FA states remain protected. Once recovery has committed, a later Stop
retains the existing stop-in-progress behavior; the normal explicit Force Stop
control remains separate.

A candidate canceled by the rechecks, or a recovery that fails preparation, is
not rearmed by repeated window notifications in the same JVM. This conservative
latch prevents stale-dialog loops; that failure requires manual intervention.

For an established session, the exact modal is dismissed and File / Close runs
on a dedicated worker. LoginState is not falsified to choose a different shutdown
branch. An independent 30-second deadline handles an unresponsive close or UI;
it can halt only the current Gateway JVM and only with the prepared regular
expiry marker still present. It never kills arbitrary processes or other profiles.
Failures are observable through fixed, generation-checked Manager error events.

Manager revokes the old login/main-window readiness and displays the recovery
reason. An event requests recovery; it does not itself prove process exit,
replacement startup or successful authentication. Invalid-twsInfo reporting
retains precedence.

## Fresh startup and retry scope

Only confirmed expiry writes `TOKENEXPIRED<ibcsessionid>`. The wrapper consumes
that session-specific marker before looking for a warm-restart descriptor,
clears the restart option and starts the replacement without `-Drestart`.
It does not scan or delete another session's authentication files.

The wrapper records `TOKENEXPIREDPENDING<ibcsessionid>` before replacement.
Successful `LOGIN_LOGGED_IN` clears that exact guard. If the replacement exits
before successful authentication, the guard prevents falling back to stale
warm data. Conflicting or unconsumable recovery markers, or failure to record
the required guard, produce an explicit error rather than another automatic
expiry cycle. Ordinary absence of these markers is valid. Failed guard cleanup
is also reported.

Existing explicit 2FA-timeout and login-display-timeout retry policies remain
separate and run with the cleared restart option. This is not an unconditional
limit on every existing fresh-login retry policy. Ordinary warm restart, Pause,
generic `ColdRestartTime`, and unrelated Stop paths retain their behavior.

Any IBKR-required IB Key approval remains a user action. No Sunday timer needs
to be configured for this error-triggered recovery.

## Exact runtime pins

The managed workaround selects only JBR `25.0.4-b508.27` and JavaFX `25.0.4`,
including all eight required modules and Windows natives. Selection requires
no version-catalogue request. If a pinned artifact is unavailable, setup fails
without selecting a newer release.

Download validation and cached-payload reuse verify exact JavaFX module identity
and version, with the existing payload hashes and ownership checks. Matching
working payloads are reused. A mismatched owned payload is not silently replaced;
the existing explicit disable/Save on all owners followed by enable/Save remains
the maintenance path. Older owned payloads can still be removed safely. Manual
runtime installations and Manager's own Java bootstrap remain unchanged.

## References and validation

- `engine/src/main/java/ibcalpha/ibc/ExpiredSessionTokenDialogHandler.java`: recognition and eligibility.
- `engine/src/main/java/ibcalpha/ibc/ExpiredSessionTokenRecovery.java`: scoped marker, worker, deadline and completion acknowledgment.
- `engine/src/main/java/ibcalpha/ibc/StopTask.java`, `RestartTask.java`, `ReloginDialogHandler.java`: action arbitration.
- `engine/resources/scripts/StartIBC.bat`: explicit expiry precedence and failed-fresh guard.
- `src/main/java/io/github/ibcmanager/runtime/IbcLogStateParser.java`: recovery and failure state reporting.
- `src/main/java/io/github/ibcmanager/workaround/RuntimeCatalog.java`, `RuntimeBundleInstaller.java`, `ManagedRuntimeService.java`: exact pins, installation and reuse.
- Engine headless and GUI suites, `ExpiredTokenEventTests`, `TokenExpiryWrapperTests`, and workaround suites: regression coverage.

See [TEST_REPORT.md](../TEST_REPORT.md) for newly executed 2.3.0 checks and
outstanding native Windows / GUI / live Gateway acceptance. The submitted
2.2.0 Windows results are historical evidence, not execution of the new code.
