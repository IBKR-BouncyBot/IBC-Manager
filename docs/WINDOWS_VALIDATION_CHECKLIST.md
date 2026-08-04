# Windows release validation checklist

Record the Windows build, Java/JDK version, IBC version, offline TWS/Gateway
version, test account, network/proxy conditions, and tester name with the
completed checklist.

## Prerequisite bootstrap

Use a disposable clean Windows 11 VM for destructive/failure-path tests.

- [ ] With only Java 8 on `PATH`, `run.bat` identifies Java 8 as incompatible and
  does not attempt to launch the JAR with it.
- [ ] `run.bat`, `build.bat`, and `package-windows.bat` reach prerequisite
  detection without `Cannot bind argument ... because it is an empty collection`.
- [ ] The same entry points do not fail with `Cannot overwrite variable Home
  because it is read-only or constant`; the Java check or explicit permission
  prompt is reached instead.
- [ ] Answering `N` at the installation prompt returns a nonzero status and
  creates no `%LOCALAPPDATA%\IBCManager\tools` installation.
- [ ] Answering `Y` installs Microsoft OpenJDK 17 under the current-user tools
  directory and launches IBC Manager successfully.
- [ ] Existing Java 8 remains installed and `java -version` in a new terminal is
  unchanged after the private Java 17 installation.
- [ ] No user or machine `PATH`/`JAVA_HOME` environment value changes.
- [ ] A pre-existing compatible Java 17+ runtime is detected without prompting.
- [ ] A Java 17 installation later on `PATH` is found even when Java 8 appears
  first.
- [ ] `IBC_MANAGER_JAVA_HOME` selects a valid explicit Java home and rejects an
  invalid or Java 8 home before falling back safely.
- [ ] Microsoft OpenJDK download succeeds on x64; ARM64 is checked when that
  platform is supported for release.
- [ ] A checksum mismatch or truncated Java archive stops before extraction or
  activation.
- [ ] `test.bat`, `build.bat`, and `validate-windows.bat` request only a full
  Java 17+ JDK when build tools are missing; no Apache Ant requirement or download
  is shown.
- [ ] On a machine where `ant` is absent from `PATH` and not installed anywhere,
  `test.bat`, `build.bat`, `validate-windows.bat`, and `run.bat` from the source
  archive all complete their JDK-native build path.
- [ ] Blocking every Apache download host does not affect any supplied Windows
  launcher or build because version 1.0.6 makes no Ant network request.
- [ ] The in-repository `BuildProject.java` source driver is executed through the
  bootstrap-selected Java executable and creates Java 17 bytecode with warnings
  treated as errors.
- [ ] Start two mutating build commands from the same extracted source tree at
  the same time. The second reports that another build is active, waits, then
  completes after the first; neither process loses classes or reports an
  intermittent `ClassNotFoundException`.
- [ ] Run builds concurrently from two different extracted source trees and
  confirm their independent project locks do not block one another.
- [ ] When Ant is installed, overlap `ant test` with `build.bat` in the same
  source tree. The Ant wrapper must report/wait on the same project lock and both
  commands must finish without deleting each other's classes.
- [ ] Interrupting a managed-tool activation leaves either the prior verified
  tool or no active managed tool, never a partially activated directory.
- [ ] Simulate failure to delete the obsolete `.old-*` directory after successful
  activation; the new verified tool remains active and a visible warning is shown.
- [ ] Two simultaneous bootstrap invocations serialize and both complete without
  corrupting the managed tools directory.
- [ ] Offline, blocked-TLS, and corporate-proxy failures produce actionable
  errors and remove temporary archives/staging directories.
- [ ] The temporary prerequisite environment `.cmd` file is deleted after
  successful import and after a forced failure.
- [ ] Tool paths containing spaces work. Test a Windows user/profile path with
  non-ASCII characters and document the active code page.
- [ ] With a path not representable by the current console code page, bootstrap
  fails before import without replacement characters; after `chcp 65001`, retry
  succeeds when the installed Windows version supports UTF-8 batch paths.
- [ ] `validate-windows.bat` passes the PowerShell `-SelfTest` gate in built-in
  Windows PowerShell 5.1.
- [ ] From the binary release, `run.bat` finds the root versioned JAR, uses only
  runtime prerequisites, completes the console-visible JAR preflight, and opens
  the GUI.
- [ ] From a freshly extracted source ZIP with no `dist` directory or JAR,
  `run.bat` explains the on-demand build, requests JDK permission when necessary,
  creates `dist\IBC-Manager-1.0.19.jar`, smoke-tests it, and opens the GUI without
  requiring `build.bat` first.
- [ ] Corrupt a copy of the release JAR and confirm `run.bat` reports the Java/JAR
  failure in the console and does not attempt the detached `javaw` launch.
- [ ] `package-windows.bat` detects a missing WiX installation and asks
  permission before invoking WinGet.
- [ ] Declining WiX installation makes no WinGet call.
- [ ] Approved WiX installation uses exact package ID
  `WiXToolset.WiXToolset`; expected UAC behavior is documented.
- [ ] Missing/disabled WinGet fails before Java package-mode changes and
  gives a manual WiX instruction.
- [ ] A successful `package-windows.bat` run creates the app image under
  `dist\windows\IBC Manager`, the versioned installer
  `dist\windows\IBC Manager-1.0.19.exe`, and
  `dist\IBC_Manager_1.0.19_Release_windows.zip`.
- [ ] The Windows release ZIP contains `IBC Manager-1.0.19.exe` at its root and
  one complete portable `IBC Manager` folder at its root.
- [ ] The portable folder contains nonempty `IBC Manager.exe`, the versioned
  application JAR, and `runtime\bin\java.exe`.
- [ ] The Windows release ZIP contains no normal-release JAR, batch launcher,
  README, documentation, notice, licence, or checksum file outside the portable
  app image.
- [ ] Add a second direct `.exe` file under `dist\windows` immediately before
  invoking the `windows-release-zip` build target and confirm the target rejects
  ambiguous installer output instead of creating a release archive.

## Portable-runtime regression introduced in 1.0.12

- [ ] Run `package-windows.bat` from a freshly extracted 1.0.19 source ZIP.
- [ ] Confirm both jpackage commands show the explicit jlink option list without
  `--strip-native-commands`.
- [ ] Confirm `dist\windows\IBC Manager\runtime\bin\java.exe` exists and is
  nonempty before the EXE installer stage begins.
- [ ] Confirm `dist\IBC_Manager_1.0.19_Release_windows.zip` contains the exact
  nonempty entry `IBC Manager/runtime/bin/java.exe`.
- [ ] Extract only the portable `IBC Manager` folder to a new directory, start
  `IBC Manager.exe`, and start a paper profile. The profile must not report
  **Could not locate Java runtime**.
- [ ] Confirm the detached process relay remains alive while IBC/Gateway runs,
  live output appears in the Logs tab, and the manager-owned process log receives
  its final batch when the managed process exits.
- [ ] Install with `IBC Manager-1.0.19.exe`, start the installed application, and
  repeat the paper-profile launch test. The installed runtime must also contain
  `runtime\bin\java.exe`.
- [ ] Remove or rename `runtime\bin\java.exe` in a copy of the app image and
  confirm the Windows release-ZIP build target rejects it rather than publishing
  an incomplete archive.

## Windows process-tree regression introduced in 1.0.11

- [ ] From a freshly extracted 1.0.19 source ZIP, both `validate-windows.bat`
  and `package-windows.bat` pass the test named **process tree terminator captures
  descendants spawned during cooperative shutdown**.
- [ ] Repeat the complete Windows test gate at least three times; the dynamic
  descendant test does not fail with a missing child-PID file.
- [ ] Confirm the test uses the Java fixture's standard-input signal and does not
  depend on a JVM shutdown hook running after an external process termination.
- [ ] Confirm the spawned child is terminated while an unrelated Java process
  remains alive.
- [ ] After the complete tests pass, `package-windows.bat` proceeds to the real
  `jpackage` application-image and WiX EXE stages.

## 1.0.13+ quiet command-server regression

- [ ] Start a paper profile and leave it idle for at least 30 seconds.
- [ ] Confirm IBC output does not repeatedly add `CommandServer: ControlFrom
  setting =`, `CommandServer accepted connection from`, and `Closing command
  channel` every two seconds.
- [ ] Confirm the Overview tab changes the IBC command-server field to **Ready**
  after IBC reports that the server is ready, without a monitoring connection.
- [ ] Invoke one real command and confirm one normal accepted/closed command
  channel sequence may appear for that command, but does not continue while idle.
- [ ] Close and reopen IBC Manager while the profile remains running. One passive
  listener-table observation is allowed during reattachment when the
  command-server startup line is no longer available; no command-port client
  connection should be created and subsequent refreshes must remain quiet.
- [ ] Confirm an occupied command port is still detected once during launch
  preflight before credentials or a password-bearing runtime config are loaded.

## 1.0.14 passive API-listener regression

- [ ] Upgrade from 1.0.13 or earlier, start a paper profile, and leave it idle for
  at least 60 seconds.
- [ ] Confirm the IB Gateway log does not add `Client disconnected before version
  was sent` and `API client version is missing` every two seconds.
- [ ] Confirm no new incoming API connection appears solely because the IBC
  Manager Overview tab is open or refreshed.
- [ ] Confirm the green headline is **API listener detected** and the State field
  is **API LISTENER DETECTED** when Windows reports the API port as listening.
- [ ] Confirm the detail explicitly states that the IB API handshake is not
  verified.
- [ ] Stop Gateway/TWS and confirm the passive listener status changes within the
  five-second snapshot interval without a raw API connection.
- [ ] Temporarily make the trusted Windows `netstat.exe` unavailable in an
  isolated test VM. A new profile launch must fail before credentials are loaded,
  while an already-running profile must become yellow/uncertain instead of green.
- [ ] Restore `netstat.exe` and confirm passive listener status recovers without
  restarting IBC Manager.
- [ ] Run two profiles and confirm one shared listener-table snapshot services
  both profiles; no per-profile API connection attempts are logged.
- [ ] If the repeated version-missing pair remains, close every older IBC Manager
  instance and identify any other program performing a raw TCP health check.

## 1.0.19 Properties, lifecycle, command, and path compatibility

- [ ] Import an external config containing `IbDir=C:\Jts`; validation warns that
  IBC interprets it as `C:Jts`, does not echo any credential value, and recommends
  `IbDir=C:\\Jts`.
- [ ] Enter malformed Properties text whose final line is a lone continuation
  backslash. Managed Config reports that the full-file JDK parser and formatting
  scanner disagree and does not rewrite the file without explicit confirmation.
- [ ] Confirm explicit canonicalization shows the JDK-authoritative settings,
  discards only the ambiguous raw formatting, and remains stable after reopen.
- [ ] Exercise normal `ClosedownAt`; the profile transitions through yellow
  shutdown state and ends **Stopped**, not **Error**.
- [ ] Exercise automatic restart, cold restart, login-dialog timeout recovery, and
  second-factor timeout recovery. Each shows yellow **Restarting** until the next
  `Starting IBC with this command:` generation marker.
- [ ] Cause a paper-session IBC child error that logs
  `Exiting after error with exit code=`. The status waits for the wrapper decision;
  it recovers to startup when the wrapper restarts and becomes red only if the
  wrapper exits without recovery.
- [ ] Using an isolated command-server fixture, confirm exact replies `OK` and
  `ERROR` are classified as completed and rejected respectively, while a later
  error overrides `OK ... in progress`.
- [ ] Select or type a path under `C:\Program Files (x86)`. Validation names the
  offending `(` character, lists `" % ! & | < > ^ ( )`, recommends `C:\IBC` or
  `C:\Jts`, and prevents `StartIBC.bat` launch.
- [ ] Confirm simple paths containing spaces but none of the rejected characters
  remain usable.

## 1.0.18 detached-relay Windows cleanup regression

- [ ] Run `package-windows.bat` from a freshly extracted 1.0.19 source ZIP.
- [ ] Confirm all 538 Java tests pass, including **buffered process relay
  survives the manager process exiting**.
- [ ] Confirm the test no longer fails while deleting a temporary `logs`
  directory with `The process cannot access the file because it is being used
  by another process`.
- [ ] Repeat `package-windows.bat` at least three times. No temporary
  `ibc-manager-detached-process-log-*` directory should remain after a
  successful run.
- [ ] Confirm a deliberately persistent Windows file lock still causes bounded
  cleanup failure rather than being ignored indefinitely.

## 1.0.17 locale-stable time-validation regression

- [ ] Run `package-windows.bat` under a Dutch or other non-English Windows
  display/format locale. The complete Java test gate must pass.
- [ ] In Managed config, set `AutoRestartTime=11:45 PM`, save, reopen Profile
  Edit, and confirm the value remains synchronized.
- [ ] Confirm `11:45 pm`, `23:45`, a single-digit hour, and invalid minutes are
  rejected for `AutoRestartTime`.
- [ ] Confirm `ColdRestartTime=23:45` remains valid.
- [ ] Confirm packaging proceeds beyond all 524 Java tests to `jpackage` and the
  Windows ZIP assembly.

## 1.0.16 managed-config synchronization regression

- [ ] Change an advanced setting in **Managed config**, save it, then open
  **Edit**. The structured value must match the raw file.
- [ ] Change `IbLoginId`, `TradingMode`, `MinimizeMainWindow`,
  `OverrideTwsApiPort`, `CommandServerPort`, `BindAddress`,
  `ReloginAfterSecondFactorAuthenticationTimeout`, and `SecondFactorDevice` in
  Managed config. After save, the corresponding Profile controls must match.
- [ ] Change the same values in Profile Edit and confirm Managed config contains
  the canonical values while comments and an unknown test property survive.
- [ ] Attempt to make the API and command ports identical in Managed config.
  Saving must fail and both the profile and managed file must remain unchanged.
- [ ] Enter a Windows batch metacharacter in a path through any available input
  path. Validation must fail cleanly; no launcher command may be generated.

## 1.0.16 IBC compatibility regression

- [ ] A manually selected IBC folder is rejected when the `version` file is not
  3.24.1, when `IBC.jar` embeds another version, or when the tested launcher
  capabilities are missing.
- [ ] The resolved Java directory shown in validation is the same directory
  passed to `StartIBC.bat /JavaPath:` and reports Java 17 or newer.
- [ ] A username, password, or `SecondFactorDevice` containing non-ASCII text and
  literal backslashes is received unchanged by IBC in a paper-account test.
- [ ] After authentication, the runtime config remains present while
  `StartIBC.bat` is alive and is removed after the wrapper exits.
- [ ] Exercise an IBC auto-restart or cold restart and confirm the replacement
  IBC JVM can reload the same configuration path.
- [ ] Before `Login has completed`, reconnect, pause, and enable-API controls are
  disabled. They become available only after the main-window readiness boundary.
- [ ] PAUSE first displays **Pausing** and becomes **Paused** only after the log
  contains `IBC is paused` and the wrapper exits.
- [ ] Graceful Stop timeout does not kill the process. Force Stop requires its
  separate confirmation.
- [ ] Restart performs Stop plus fresh Start and does not invoke native IBC
  `RESTART` or alter the configured auto-restart time.
- [ ] With API-port forcing disabled, IBC Manager leaves `OverrideTwsApiPort`
  blank and does not open the Gateway/TWS configuration UI solely to rewrite it.
- [ ] A listener on the configured API port owned by an unrelated process is
  shown yellow, not green. The real managed Gateway listener becomes green.
- [ ] Two profiles started simultaneously from the same offline installation do
  not race while `tws.exe`/`ibgateway.exe` is renamed.

## Status and buffered logging

- [ ] A stopped profile shows a red dot and explicit **Stopped** text in both
  the profile list and selected-profile status panel.
- [ ] Startup, login waiting, second-factor waiting, pause, and stopping show a
  yellow indicator with explicit state text.
- [ ] When the operating system reports a listener on the configured API port,
  the indicator changes to green and the headline becomes **API listener
  detected**, while the detail still states that the IB API handshake is not
  verified.
- [ ] A profile that is logged in but whose API listener is absent or cannot be
  inspected remains yellow.
- [ ] Produce application and profile-process output and confirm manager-owned
  log files do not change before the 60-second interval during normal running.
- [ ] Confirm the live Logs tab updates before the 60-second disk commit.
- [ ] Confirm the first disk commit occurs after approximately 60 seconds and
  contains the buffered records once, without duplication.
- [ ] Stop the profile before the next interval and confirm the final partial
  process-log batch is committed immediately.
- [ ] Close the manager before the next interval and confirm the final partial
  application-log batch is committed immediately.
- [ ] Confirm log files written independently by IBC/TWS/Gateway are not
  represented as controlled by the manager's 60-second setting.

## Clean-machine package

- [ ] `IBC Manager.exe` starts without a separately installed Java runtime.
- [ ] The app image and installer are signed or Windows warning behavior is documented.
- [ ] `--version` reports 1.0.19 and IBC baseline 3.24.1.
- [ ] `--headless-smoke` succeeds in a clean data directory.
- [ ] A second manager instance is rejected without corrupting the first.
- [ ] Uninstall leaves user data untouched unless explicitly selected.


## Official IBC installer and profile dialog

- [ ] **Detect common installations...** and **Install IBC 3.24.1 from GitHub...**
  are fully visible and clickable at 100%, 125%, 150%, and 200% Windows display
  scaling; the profile form scrolls vertically instead of clipping rows.
- [ ] The IBC install action asks for explicit confirmation before network or file
  changes and clearly states that IB Gateway/TWS is not included.
- [ ] On an empty destination, the official `IBCWin-3.24.1.zip` asset downloads over
  GitHub HTTPS infrastructure and activates as a valid `C:\IBC` installation.
- [ ] The downloaded ZIP equals the SHA-256 published by GitHub for the supported
  asset and pinned in IBC Manager. A deliberately altered but otherwise valid IBC
  ZIP is rejected before extraction or destination creation.
- [ ] The completion dialog reports IBC version 3.24.1, the asset name, destination,
  and a 64-character SHA-256 digest.
- [ ] A valid existing IBC 3.24.1 installation in `C:\IBC` is reused without a
  download and is selected in the profile.
- [ ] A non-empty invalid `C:\IBC` directory is never overwritten or merged.
- [ ] Cancelling during download/extraction leaves no partial destination, staging
  directory, or temporary archive.
- [ ] Offline, proxy, TLS, redirect-host, oversized-archive, corrupt-ZIP, and
  validation failures are actionable and leave the existing destination intact.
- [ ] Under a standard account, access denial for `C:\IBC` is explicit; retrying
  after an intentional administrator launch succeeds without hidden elevation.
- [ ] The **Second-factor device** field loads an existing `SecondFactorDevice=`
  value, trims it, saves exactly one canonical setting, and reaches the generated
  runtime `config.ini`.
- [ ] Clearing **Second-factor device** removes the profile override and leaves IBC
  to perform its normal/default device selection.

## Session-action confirmations and toolbar

- [ ] Start, Stop, Restart, and Pause are larger than secondary toolbar controls,
  use bold labels and four visibly distinct accents, and are not compressed at
  100%, 125%, 150%, or 200% Windows display scaling.
- [ ] Stop uses the same minimum height, width, font weight, border treatment,
  tooltip, and accessibility metadata as Start, Restart, and Pause while retaining
  its own red accent and graceful-stop behavior.
- [ ] The separate session row remains usable at the application's minimum window
  size and at 1280 x 720; no action label is clipped.
- [ ] Manual Start opens **Confirm start** and identifies the selected profile,
  application, trading mode, and API port. A live profile also displays the
  explicit live-trading warning.
- [ ] Manual Stop opens **Confirm stop**, identifies the selected profile, and
  explains that API and market-data connectivity will stop after a graceful
  shutdown.
- [ ] Manual Restart opens **Confirm restart** and explains that API and
  market-data connectivity can be interrupted.
- [ ] Manual Pause opens **Confirm pause** and explains that trading connectivity
  stops until the profile is started again.
- [ ] Cancel is initially selected in all four dialogs. Cancelling each dialog
  leaves the process identity, runtime state, and command history unchanged.
- [ ] Confirming each action dispatches exactly one operation to the selected
  profile and cannot affect another profile.

## Profile and configuration

- [ ] Common-installation discovery finds the intended IBC and offline application.
- [ ] **Install IBC 3.24.1 from GitHub...** asks for confirmation before
  network access and installs the official Windows release in `C:\IBC`.
- [ ] Cancelling the IBC download leaves no partial `C:\IBC`, staging tree,
  or temporary ZIP.
- [ ] A valid existing IBC 3.24.1 installation is reused without a download.
- [ ] A non-empty invalid `C:\IBC` is preserved and never overwritten.
- [ ] Standard-user access denial is reported clearly; an explicitly elevated
  retry succeeds when Windows policy requires elevation for `C:\IBC`.
- [ ] The installed files match the official archive structure and IBC Manager
  does not modify `IBC.jar` or `scripts\StartIBC.bat`.
- [ ] **Detect common installations...** and **Install IBC 3.24.1 from
  GitHub...** are fully visible at 100%, 125%, 150%, and 200% display scaling.
- [ ] The Profile tab scrolls vertically on a small display without clipping
  either installation button or the Save/Cancel controls.
- [ ] `SecondFactorDevice` loads, saves, clears, and selects the intended
  registered device through unmodified IBC.
- [ ] Manual path selection works with spaces and non-ASCII characters in all paths.
- [ ] Invalid paths, versions, ports, and duplicate profile resources are blocked.
- [ ] Comments, unknown keys, order, blank values, and CRLF survive raw-config save.
- [ ] Persistent manager-owned config contains blank password fields.
- [ ] A password in an earlier duplicate followed by a blank final setting is
  blocked by the raw editor and rejected by persistence.
- [ ] Password assignments in comments, malformed/raw lines, and case-variant
  sensitive keys are blocked or redacted and never appear in diagnostics.
- [ ] Profile backups recover after simulated partial/corrupt writes.

## Credentials

- [ ] DPAPI save/load succeeds for the current Windows user.
- [ ] DPAPI save/load preserves a password containing non-ASCII UTF-8 characters.
- [ ] Another Windows user cannot decrypt the credential file.
- [ ] Password does not appear in profile/config/log/diagnostic files or process command lines.
- [ ] Runtime config and every parent private directory have owner-restricted ACLs with usable traverse permission.
- [ ] Runtime config remains present after authentication while `StartIBC.bat` is
  alive and is scrubbed/removed only after the wrapper exits.
- [ ] Failed launch, normal stop, forced stop, wrapper exit, and app restart clean
  stale runtime config.
- [ ] Manager exit with an active wrapper is allowed only after the detached relay
  owns the exact cleanup path; otherwise an unremovable password-bearing runtime
  config blocks exit.

## IBC and paper Gateway/TWS

- [ ] Occupying either configured port blocks launch before a runtime credential file is created.
- [ ] Official `StartIBC.bat` is used.
- [ ] Paper Gateway starts and username/password handling matches the selected mode.
- [ ] Manual second factor remains available and completes successfully.
- [ ] Existing-session, API-warning, and common startup dialogs remain handled by IBC.
- [ ] Dashboard state follows login, 2FA, running, pause, and exit events.
- [ ] After PAUSE is accepted, Gateway/TWS exits and the dashboard remains
  PAUSED with Start available; it does not report an unexpected-exit error.
- [ ] IBC command actions work and responses are shown.
- [ ] API enablement is available for TWS and disabled for Gateway.
- [ ] Passive API-listener state matches the Windows TCP listener table.
- [ ] The trading client independently confirms the expected paper account and handshake.

## 1.0.10 filesystem and transaction hardening

- [ ] Oversized profile, managed-config, existing-config, credential, identity,
  relay-descriptor, and transaction-metadata fixtures are rejected without high
  memory growth or modification of unrelated files.
- [ ] When Developer Mode or administrator rights permit test symbolic links,
  links substituted for manager-owned profiles, configurations, credentials,
  runtime files, lock files, identities, logs, and IBC required files are
  rejected and their targets remain unchanged.
- [ ] Simulate a late managed-config/profile-save failure and verify the previous
  profile, managed configuration, and DPAPI credential are restored together.
- [ ] Simulate an interrupted PREPARED profile deletion and verify startup
  restores the profile/runtime state.
- [ ] Simulate an interrupted COMMITTED deletion and verify startup completes
  credential/tombstone cleanup without restoring the deleted profile.
- [ ] A deletion tombstone whose directory UUID differs from its metadata UUID
  cannot delete or restore another profile.
- [ ] Reattachment rejects a copied identity with a stale PID, wrong start time,
  or mismatched fingerprint.
- [ ] Terminating a test root that creates a child during shutdown removes the
  exact child and does not affect an unrelated Java process.
- [ ] An IBC command server that sends an oversized line, too much response data,
  too many lines, or data indefinitely is bounded by the configured limits and
  one total deadline.

## Process isolation

- [ ] Two profiles run simultaneously with separate settings and ports.
- [ ] Stopping one profile does not stop or modify the other.
- [ ] The explicit force-stop menu requires confirmation.
- [ ] Forced stop terminates only the selected process tree.
- [ ] Manager restart reattaches only when PID and creation time match.
- [ ] A stale identity file cannot attach to a reused PID.

## Task Scheduler

- [ ] Startup task is created as ONLOGON, interactive, limited privilege.
- [ ] One-minute delay is present.
- [ ] Paths with spaces and arguments are quoted correctly.
- [ ] Inspect the created task and start it: the `/TR` action containing a quoted
  executable path and `--data-dir` path with spaces is stored as one action and
  launches the intended manager instance.
- [ ] The GUI and IBKR dialogs appear in the logged-in user's desktop.
- [ ] Removing the task removes only the IBC Manager task.

## Diagnostics and logging

- [ ] App and profile logs rotate and remain readable.
- [ ] UTF-8 log text remains intact when a multibyte character is split across appended reads.
- [ ] Deliberately injected password syntax is redacted from logs and ZIP exports.
- [ ] Two exports in the same second create distinct files.
- [ ] Failed export leaves no partial ZIP.
- [ ] Diagnostic ZIP permissions are restricted to the current user.

## Long-run paper test

- [ ] Run continuously through at least one daily session transition.
- [ ] Exercise a scheduled IBC restart.
- [ ] Disconnect/reconnect network access.
- [ ] Restart Windows and verify automatic manager/profile startup.
- [ ] Confirm no orphaned plaintext runtime config remains after each scenario.
