> Historical review of 2.0.0. The independent 2FA timer gap R-03 is addressed
> by 2.0.1; see SECOND_FACTOR_RETRY.md and the current CODE_REVIEW.md. The
> original findings below are retained as history, not current release claims.

# Independent requirements and source review - IBC Manager 2.0.0

Review date: 2026-10-08. Same-version re-release; the application remains 2.0.0
and the unchanged embedded engine remains 3.24.2-manager.1.

## Basis and scope

This is a fresh source-based review, not a third-party certification and not a
repetition of the previous release's pass/fail claims. Its basis is the original
Windows/Gateway-only integration request, the supplied Manager 2.0.0 source ZIP,
and the supplied IBC 3.24.2 source and Windows release ZIPs. No inferred newer IBC
implementation was substituted. No real IBKR account was used.

Input hashes (SHA-256):

- Original Manager source: `8645ee8cbcb30609d546b3542e7c9d7b17bf5322e70fc13aa6011006d0a5f31f`
- IBC source: `8e0d2390a619bbcc2de2fb159a8dd8f0acff0a107731d08ec73e999e1cc27d84`
- IBC Windows release: `ba8e95f61f3c7c252620baef41e550c6aab98f6034179156e11a9a1623fdd892`

The review traced Manager startup, embedded-engine deployment, launch construction,
profile/config migration, structured events, command gating, stalled-start recovery,
credential/config lifetime, batching, packaging and the new console/About changes.
Every maintained engine file was compared with the original source. All seven
modified upstream Java files and both added engine classes were inspected. This
is not a claim to have dynamically exercised every unchanged upstream dialog handler.

The original source independently built and passed 590 named cases / 8,336
assertions. That baseline success did not prevent the recovery defects below.

## Requirements matrix

| Original requirement | Source evidence | Assessment |
|---|---|---|
| Integrate a maintained IBC engine | `BuildProject.compileEngine`, `EmbeddedEngine`, `engine/src/main/java` | Implemented: engine is compiled from included source and embedded in the Manager JAR. |
| Remove separate IBC installation support | `LaunchScriptFactory.create`, `ProfileCodec`, `ProfileEditorDialog`, `EmbeddedEngineTests` | Implemented: generated launch uses only the embedded payload. Legacy external paths are inert migration metadata, not an alternative engine. |
| Keep the engine maintainable/updatable | `engine/PROVENANCE.json`, `UPSTREAM_SHA256.json`, reference source, patch, `MAINTENANCE.md` | Implemented: explicit upstream revision, byte hashes, separate engine revision and reproducible patch. No runtime latest-download replacement. |
| Only IB Gateway is supported | Manager type validation, `IbcGateway.main`, rejected `IbcTws.main` | Implemented: normal IB API Gateway only. Shared TWS-named internals and the reference snapshot are retained; this is not executable TWS/FIX support. |
| Only Windows is supported | `IbcManagerApp.run`, `IbcGateway.main`, Windows launch resources | Implemented for the product. Linux is a build/test host only. |
| Detect/import existing Manager configuration | `AppPaths.systemDefault`, `LegacyUpgradeService`, `ProfileCodec`, `ManagedConfigService` | Implemented as default-directory in-place detection/migration, or explicit `--data-dir` for a custom directory. It does not automatically search every arbitrary disk folder. |
| Preserve upgrade credentials/settings | profile UUIDs, unchanged DPAPI association, per-profile original backups and pending transaction recovery | Source and fixture coverage present. Actual DPAPI decryption/ACL acceptance is still a Windows gate. No cross-user credential portability claim. |
| Preserve Gateway functionality 1:1 | upstream comparison and integration patch below | Source preservation demonstrated; complete operational parity remains unproven without real Gateway acceptance. |
| Add tests for IBC code | actual `EngineContractTests`, `EngineGuiSmoke` | Implemented: 14 actual-engine headless cases plus four Swing/command scenarios with 26 checks. This is not exhaustive engine branch coverage. |
| Package as 2.0.0 | `Version`, build driver, Windows packaging scripts | Normal JAR/release/source reproduced locally. Genuine Windows installer/portable image must be built on Windows. |
| Keep unattended recovery | `ProfileRuntimeController`, `RecoveryHistoryStore`, `StartupStallDetector` | Implemented, with two review-discovered safety defects corrected below. Manager must remain running for its watchdog. |
| Repeat 2FA notification after exactly five minutes | `SecondFactorPolicy`, engine `LoginManager.secondFactorAuthenticationDialogClosed` | **Not a strict timer.** Native behavior is retained with a 300-second threshold; see R-03. |

## Upstream comparison

The supplied upstream source contains 83 Java files. The maintained engine has
85: 76 are identical after line-ending normalization, seven upstream files have
localized changes, and two files are added. No upstream Java file was removed.
The archived reference bytes are unchanged: all 89 files in the reference hash
manifest match. The original Windows `StartIBC.bat` and
`getExtraJavaOptions.ps1` also match the supplied release byte-for-byte.

Modified upstream files:

- `IbcVersionInfo`: deterministic version declaration, same 3.24.2 value.
- `IbcTws`: Windows product's Gateway-only bootstrap, reflection instead of an
  unavailable proprietary compile-time dependency; TWS entry rejects execution.
- `IbcGateway`: Windows/IB API-only guard and initial lifecycle message.
- `CommandServer`: bounded command-server lifecycle events.
- `CommandDispatcher`: reject reconnect before a main window exists.
- `LoginManager`: non-secret login-state events; its original relogin logic remains.
- `GatewayMainWindowFrameHandler`: main-window lifecycle event.

Added: `EngineEvents` and `GatewayEntrypoint`.

Applying `engine/patches/0001-manager-integration.patch` to an LF-normalized copy
of the original Java tree reproduced every maintained Java file byte-for-byte.
These are maintainability/provenance checks, not a substitute for a real Gateway
startup, authentication, restart or command test. The engine and its payload
are unchanged by this same-version re-release.

## Review findings

### R-01 - High: stale recovery evidence after the graceful STOP wait - fixed

`ProfileRuntimeController.runAutomaticRecovery` originally checked the startup
stall before sending STOP, then waited up to 15 seconds and could terminate the
process without checking whether login, 2FA, API readiness or a new engine child
had appeared during that wait. Its queued worker could also use parser state
that predated output arriving after the recovery was enqueued.

The added regression cases first failed against that production implementation.
The fix drains current owned output, rechecks the wrapper/child generation and
stall condition, and observes the API listener afresh before STOP and again
immediately before force cleanup. New progress cancels escalation and refreshes
the displayed state. No replacement wrapper is launched by the cancelled task.

A STOP already delivered to IBC cannot be recalled. If it later completes,
ordinary process-exit handling still applies. The fix does not claim an atomic
transaction with Gateway's independent threads; it removes the stale 15-second
and queued-work decision windows reproduced by the tests.

### R-02 - High: uncertain API inspection did not block destructive cleanup - fixed

The old recovery path verified port release after termination, but did not
consistently require a current absence observation before termination. If
listener inspection failed during the STOP wait, the process could still be killed.

The same new safety gate refuses further termination on an UNKNOWN observation
or a listening port with unverified/unrelated ownership. The process remains
tracked, no replacement is launched, and `RECOVERY_FAILED` reports the reason.
The existing port-release gate and persistent retry limits remain intact.

Six named tests reproduce the before-fix failures, including queued progress,
late login/2FA/API, replacement generation, and unknown listener observations.
The before- and after-fix logs are included in the validation-log archive.

### R-03 - Functional qualification: five-minute notification timing - not changed

The actual engine `LoginManager.setLoginState(TWO_FA_IN_PROGRESS)` records state
and the login start time; it does not schedule a 300-second retry. The handler
calls `secondFactorAuthenticationDialogClosed()` on WINDOW_CLOSED. That method
compares elapsed login time to `SecondFactorAuthenticationTimeout`; on the timeout
branch it schedules a native relogin five seconds later. On the shorter-duration
branch, enabled retry can instead schedule an exit if login still fails to complete.

Therefore `SecondFactorAuthenticationTimeout=300` is a threshold in Gateway's
dialog-close flow, not a deadline guaranteed to fire while the dialog remains
open. Nor is it necessarily measured from the phone notification itself.

An independent fresh-JVM probe configured a one-second threshold and left the
provider in TWO_FA_IN_PROGRESS without a dialog-close event. After 1.25 seconds,
the state remained pending and no timeout task had been scheduled. This is a
scaled engine behavior probe, not a real phone test and not a five-minute wait.
The source establishes the same absence of an independent timer at 300 seconds.

The GUI text, code comment and current documentation now state this limitation.
Authentication behavior was not silently replaced during a same-version console/
credit refresh. A strict five-minute retry while a Gateway dialog remains open
needs a separately designed engine change and Windows paper-account acceptance.
It must not be described as already delivered or as bypassing phone approval.

### R-04 - Console output disappeared on exit - fixed

All five user-facing entry points now have a common completion path reached on
success and failure: run, test, build, validate-windows and package-windows. Root
launchers delegate to the canonical scripts, so there is only one pause.
The exit code is captured before the pause and returned unchanged.

Interactive `run.bat` runs the selected console Java executable synchronously:
runtime output remains visible, and the final pause occurs after the GUI exits.
For noninteractive callers set `IBC_MANAGER_NO_PAUSE=1`. A defined `CI` variable
also suppresses the pause. The inherited engine launcher, generated profile
launchers, bootstrap helper and scheduled app startup never use this pause.
They must not wait for keyboard input during unattended operation.

Native CMD tests are supplied for root/direct failure paths, an interactive
completion, exit-code preservation, and CI/explicit opt-out. Those branches are
conditional on Windows and were not executed on this Linux host. The structural
script tests and packaged-helper checks were executed here.

### R-05 - About attribution was absent - fixed

The real About dialog now explicitly thanks Richard L King (rlktradewright),
author/long-time maintainer of IBC, and also credits Steven M. Kearns and upstream
contributors. Attribution follows the supplied upstream README History and
copyright notices, not an inferred current role. Existing GPL notices remain.
The GUI smoke displays the real About dialog, verifies the acknowledgement is
visible and tests its Close button.

## Evidence and remaining acceptance

The final test report records current measured counts; the old test report is not
used as evidence for the new artifacts. The release gate covers strict compilation,
headless tests, both real Swing fixture runners, repeated builds, clean source
reproduction, payload identity, ZIP safety and checksums. Fixtures do not execute
proprietary Gateway code or send real orders/authentication requests.

Outstanding: native CMD/PowerShell, DPAPI, NTFS, Task Scheduler, jpackage/WiX,
real Gateway 10.45/10.50 and selected later installations, token/cold restart,
missed phone approval, process hangs and multi-profile Windows operation.
Structured events supplement stdout and command-server replies; a new authenticated
RPC protocol and removal of plaintext runtime INI files were not implemented.
Those were optional architecture proposals, not claims made by this release.

## Conclusion

The maintained, embedded, Windows/Gateway-only product and upgrade paths are
present in the source. The review found and fixed two substantive recovery safety
problems that the previous passing suite did not detect. Console completion and
attribution requests are implemented. Complete live 1:1 Gateway parity is not
established, and a strict five-minute notification-resend timer is a known remaining
functional gap. This re-release must not be represented as unconditional unattended
live-trading certification.
