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
  creates `dist\IBC-Manager-1.0.8.jar`, smoke-tests it, and opens the GUI without
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
  `dist\windows\IBC Manager-1.0.8.exe`, and
  `dist\IBC_Manager_1.0.8_Release_windows.zip`.
- [ ] The Windows release ZIP has one `IBC_Manager_1.0.8` root and contains all
  normal release files, `IBC Manager-1.0.8.exe`, and `SHA256SUMS.txt`.
- [ ] The SHA-256 in the archive's `SHA256SUMS.txt` matches the installer bytes
  after extraction.
- [ ] Add a second direct `.exe` file under `dist\windows` immediately before
  invoking the `windows-release-zip` build target and confirm the target rejects
  ambiguous installer output instead of creating a release archive.

## Clean-machine package

- [ ] `IBC Manager.exe` starts without a separately installed Java runtime.
- [ ] The app image and installer are signed or Windows warning behavior is documented.
- [ ] `--version` reports 1.0.8 and IBC baseline 3.24.1.
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
- [ ] Runtime config is removed after the second-factor/running state.
- [ ] Failed launch, normal stop, forced stop, and app restart clean stale runtime config.
- [ ] Manager exit is blocked while a password-bearing runtime config cannot be removed.

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
- [ ] API TCP state matches the configured socket.
- [ ] The trading client independently confirms the expected paper account and handshake.

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
