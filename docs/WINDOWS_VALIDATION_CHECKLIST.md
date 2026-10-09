# Windows acceptance - IBC Manager 2.0.3

These are acceptance requirements, not claims of execution on the Linux build host.
Keep a stopped full data-directory backup and a rollback release before testing.

## Build and package

Run package-windows.bat from a clean extracted source folder. Verify the full test
and three GUI suites pass, and the final exit code is zero before the console pause.
Verify the _Release_windows.zip contains only the installer and portable application,
including app payload and runtime/bin/java.exe. Start both native forms independently.
Do not run old/new versions against the same active data directory.

## Status wording and screenshot

Confirm green reads "Gateway running - API listener available", Overview uses
friendly status labels, and the profile list uses compact two-line entries. Hover
over the API row to see the trading-client verification explanation. No permanent
"API listener detected means ..." paragraph should appear below Overview. Confirm
unknown listener inspection remains cautionary and is not presented as a closed
port. Stop, startup, login/2FA and recovery labels should still match actual state.
The README screenshot is a synthetic GUI smoke capture, not live broker evidence.

## Configuration import and one editor

Stop old Gateway/Manager; start 2.0.3 under the same account and data directory.
Check original backup and profile format 6, retained UUID/password association, paths,
ports, schedules and retry choice. With a test external-config profile, confirm one-time
DPAPI password import and that the original INI is unchanged. Disconnect/remove that
source only after preserving a test copy; it must no longer affect the migrated profile.

Check Edit contains Profile, Gateway settings and Windows startup. There must be no
Managed Config menu/button, base-file selector or obsolete 2FA exit dropdown. The table
shows imported values and their source; blank uses the documented fallback/preserve rule.
Change an engine value and check generated runtime settings. Changing generated config.ini
must not affect the Profile or future launch. Verify the menu and toolbar refuse editing
during recovery, including its cooldown after the old process exits.

## Real second-factor challenge

Use a session that actually requests IBKR Mobile approval. Do not confuse fixtures with
actual Gateway. Leave a prompt pending for five minutes and separately dismiss a phone
notification without approving it. Verify ARMED then DUE, recognized cancellation when
needed, credential restoration and one local login submission. Confirm actual phone
receipt separately, approve, and check later retries stop. Repeat with early approval,
retry disabled, Stop before deadline and a naturally returned empty-password login form.

Export a fresh Diagnostics ZIP immediately after failure and include a credential-free
screenshot of Gateway. The old 1.0.22 archive cannot diagnose a 2.0.2 run. Do not remove
safety checks or enable password logging to troubleshoot a missing/ambiguous form.

## Other lifecycle and recovery

Check each supported installed Gateway version, scheduled warm/cold restart, Pause/Start,
Stop, controlled Restart, command gating, Manager exit/reattach and bounded startup-stall
recovery. Verify no unrelated profile/process is killed, no passive-probe log spam, no
persistent plaintext profile password and owner-only runtime/backup access. Confirm
Windows startup actions inside Profile retain the correct EXE/JAR and custom data path.
BouncyBot must independently verify API handshake, account/mode and permissions.

Runtime config remains present after authentication while `StartIBC.bat` is
alive, including scheduled warm/cold retries. Verify owner-only access while live
and final cleanup after wrapper exit, including exit while the Manager GUI is closed.
