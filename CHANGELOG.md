# Changelog

## 1.0.19 - 2026-08-04

### IBC configuration fidelity

- Made one full-file Java `Properties.load` operation the authoritative semantic
  parser for imported IBC configurations. The line scanner now exists only to
  preserve comments, ordering, and physical formatting.
- Added explicit mismatch detection between the authoritative JDK map and the
  formatting scanner. Manager-owned rewrites are blocked until malformed syntax
  is corrected or explicitly canonicalized; ephemeral external runtime copies
  canonicalize the authoritative map without inventing scanner semantics.
- Added deterministic differential coverage against `Properties.load(InputStream)`
  across 2,000 malformed/random byte sequences and the final-continuation edge
  case that previously disproved exact scanner equivalence.
- Added a non-secret warning for imported raw values containing suspicious single
  Windows backslashes, including guidance that `C:\Jts` is consumed as `C:Jts`
  and a literal path requires `C:\\Jts`.

### IBC lifecycle and command compatibility

- Added the yellow `RESTARTING` state for automatic restart, cold restart,
  login-dialog timeout recovery, and second-factor timeout recovery reported by
  the official `StartIBC.bat` wrapper.
- Classified the exact `Normal exit`, `Gateway finished at`, and `TWS finished at`
  wrapper markers as normal or scheduled shutdown completion. A configured
  `ClosedownAt` no longer becomes a persistent red unexpected-exit error, while
  unrelated text such as `Abnormal exit` cannot mask a crash.
- Recognized IBC 3.24.1's exact `Exiting after error with exit code=` message and
  kept the status non-terminal while the wrapper decides whether to restart.
- Ensured an IBC-reported error outranks the generic `Normal exit` and
  `Gateway/TWS finished at` footer that `StartIBC.bat` emits on its shared exit
  path. A child failure cannot be reclassified as a scheduled stop merely because
  the wrapper later exits with code zero.
- Accepted exact bare `OK` and `ERROR` command lines in addition to the normal
  `OK ...` and `ERROR ...` replies. A later error still overrides a preliminary
  `OK ... in progress` acknowledgement.

### Windows path policy and diagnostics

- Expanded profile-validation and launch exceptions to identify the exact unsafe
  character that cannot be transported through official `StartIBC.bat`.
- Documented the complete fail-closed character set (`" % ! & | < > ^ ( )`) and
  recommended batch-safe locations such as `C:\IBC`, `C:\Jts`, and
  `C:\IBKRSettings`. Paths under `C:\Program Files (x86)` remain intentionally
  unsupported because official batch processing re-expands the parentheses.
- Added the same guidance to Profile-editor path tooltips, README, user guide,
  architecture notes, security documentation, and Windows validation checklist.

### Validation

- Expanded the suite to 538 tests and 8,040 assertions.
- Retained Java 17 targeting, warnings-as-errors, deterministic archive generation,
  source/release extraction gates, GUI smoke testing, and Windows script policy
  checks.

## 1.0.18 - 2026-08-04

### Fixed

- Fixed the final Windows package-gate failure in **buffered process relay
  survives the manager process exiting**. The relay and child test fixture no
  longer use the disposable log directory as their current working directory,
  avoiding a Windows current-directory handle race during cleanup.
- Added bounded Windows retry handling for transient `FileSystemException`
  failures while deleting test temporary trees. Permanent failures still stop
  immediately, and transient failures still fail after five seconds.
- Kept production detached-relay, process, and logging behavior unchanged; the
  failure occurred after the relay output and exit assertions had completed.

### Validation

- Added deterministic tests for transient retry success, permanent-failure
  fail-fast behavior, and bounded retry exhaustion.
- Expanded the suite to 525 tests and 5,983 assertions.
- Repeated the detached-relay regression and complete deterministic release
  builds before publishing the artifacts.

## 1.0.17 - 2026-08-04

### Fixed

- Made `AutoLogoffTime` and `AutoRestartTime` validation independent of the
  IBC Manager JVM's host locale by pinning the documented, case-sensitive
  English `AM`/`PM` grammar. A valid value such as `11:45 PM` is no longer
  rejected on Dutch Windows installations.
- Fixed the four downstream Windows build failures in managed-config
  synchronization and runtime-config tests that were all caused by the same
  locale-sensitive time validator.
- Retained the strict IBC 3.24.1 two-digit `hh:mm AM/PM` behavior and continued
  rejection of lowercase `am`/`pm`.

### Validation

- Added a fresh-JVM regression probe that runs with `user.language=nl` and
  `user.country=NL`.
- Expanded the suite to 524 tests and 5,978 assertions.
- Ran the complete suite successfully under both the normal release locale and
  an explicitly selected Dutch JVM locale.

## 1.0.16 - 2026-08-04

### Fixed

- Made Managed config and Profile Edit genuinely bidirectional. Raw changes to
  represented Profile fields and advanced settings now synchronize to the
  profile model; structured edits update Managed config without discarding
  comments or unknown/future properties.
- Prevented stale profile-setting snapshots from overwriting raw managed edits
  at runtime.
- Added transactional rollback when a raw edit would produce an invalid profile
  or cross-profile port conflict.
- Fixed the Windows build test that attempted to construct an unsafe path before
  validating it; host-level invalid-path rejection is now handled portably while
  production batch safety remains fail closed.
- Tightened IBC 3.24.1 value compatibility for whitespace, key case, enum case,
  12/24-hour times, schedules, timeout ranges, and order-ID reset policy.
- Blocked unsupported FIX CTCI mode and FIX credentials, and removed the
  FIX-only `TrustedTwsApiClientIPs` option from structured editing.
- Blocked Gateway read-only login and added application-specific warnings for
  TWS-only or Gateway-ignored settings.
- Normalized blank `ConfirmOrderIdReset` to `ignore/ignore` before runtime.

### Validation

- Expanded the suite to 523 tests and 5,976 assertions.
- Repeated the source-level IBC Manager/IBC 3.24.1 compatibility scan and added
  regression coverage for every new boundary rule.

## 1.0.15 - 2026-08-04

### IBC 3.24.1 compatibility corrections

- Preserved the runtime `config.ini` path for the complete lifetime of the
  official `StartIBC.bat` wrapper so automatic restart, cold restart, login
  timeout, and 2FA recovery can start another IBC JVM with the same valid file.
  The detached relay owns secure cleanup after the wrapper exits.
- Replaced the partial config parser/UTF-8 writer with Java Properties-compatible
  parsing and canonical ASCII/ISO-8859-1 output. Generated files are verified by
  `Properties.load(InputStream)` before writing, including Unicode and literal
  backslash round trips.
- Added command capability gating at IBC's confirmed `Login has completed`
  boundary. RECONNECTDATA, RECONNECTACCOUNT, PAUSE, and ENABLEAPI cannot be sent
  while the main window is unavailable or a termination operation is pending.
- Classified complete IBC command responses so a later `ERROR` overrides a
  preliminary `OK ... in progress`. PAUSE remains `PAUSING` until the wrapper
  reports `IBC is paused`; an unconfirmed exit is an error.
- Replaced native IBC RESTART with controlled graceful Stop followed by fresh
  Start, avoiding IBC's fallback that changes the persistent auto-restart time.
- Removed silent escalation from graceful Stop to process-tree termination.
  A timeout leaves the process running and requires the separately confirmed
  Force Stop action. The default timeout is now 90 seconds.
- Coupled the wrapper `/On2FATimeout` action to IBC's
  `ReloginAfterSecondFactorAuthenticationTimeout` setting and reject incoherent
  restart policies.
- Resolved, executed, and version-checked the exact Java runtime passed through
  `/JavaPath`; IBC 3.24.1 requires Java 17 or newer.

### Installation, listener, and launch hardening

- Made `OverrideTwsApiPort` optional and disabled it for new profiles. Version 1
  profiles migrate with their previous forced behavior preserved.
- Strengthened manual IBC validation: external version, embedded JAR version,
  required classes, and 3.24.1-specific Windows launcher capabilities must agree.
- Required Windows green API-listener status to match a PID in the managed
  process tree. Wrong-process and ownership-unavailable listeners remain yellow.
- Rejected all relevant CMD metacharacters and Windows-ambiguous path segments
  before calling the official batch launcher.
- Serialized concurrent starts per canonical offline TWS/Gateway program
  directory through JVM and cross-process locks until StartIBC completes its
  executable-rename phase.
- Moved runtime configuration below the validated TWS settings tree so the
  config path is subject to the same strict batch-safe path policy.
- Added profile format 2 fields for explicit IBC Java, 2FA internal relogin,
  optional API-port override, and a safer 90-second graceful-stop default.

### Validation

- Expanded compatibility and regression coverage to **505 tests** and **5,816
  assertions** across **112 production** and **24 test** Java source files.
- Added direct Java Properties conformance, multi-JVM wrapper-lifetime,
  preliminary-ACK/final-error, main-window gating, listener PID ownership,
  exact IBC version coherence, Java-resolution precedence, controlled restart,
  non-forcing stop, and cross-process startup serialization tests.

## 1.0.14 - 2026-08-03

### Passive IB API listener monitoring

- Removed the remaining two-second connect-and-close health check from the
  configured IB API port. The old check disconnected before sending an IB API
  version handshake, causing IB Gateway to log `Client disconnected before
  version was sent` and `API client version is missing` on every refresh.
- Added passive TCP-listener discovery from the operating-system socket table:
  trusted `netstat.exe` on Windows, `/proc/net/tcp` and `/proc/net/tcp6` on
  Linux, and `netstat` on macOS.
- Shared and cached listener snapshots across all profiles, so normal monitoring
  neither connects to the API port nor repeatedly launches one inspection per
  profile.
- Changed launch preflight to force a fresh passive snapshot before credentials
  are loaded. Startup now fails closed when listener inspection is unavailable
  instead of assuming that the API and command ports are free.
- Added a bounded stale-snapshot bridge for transient runtime inspection
  failures. Once the bound expires, status becomes explicitly uncertain rather
  than falsely green or falsely closed.
- Renamed the green internal and user-facing state from `API_SOCKET_OPEN` to
  `API_LISTENER_DETECTED`. The state continues to state that no IB API handshake,
  account, permissions, or trading-readiness validation has occurred.
- Updated diagnostic, Overview, list-renderer, status-indicator, security, user,
  architecture, and Windows acceptance documentation to use listener terminology.

### Validation and regression hardening

- Increased the suite to **485 automated tests** and **5,526 assertions** across
  **102 production** and **24 test** Java source files.
- Added Windows and Linux listener-table parser tests, an integration test that
  proves monitoring detects a bound server without accepting a connection,
  cache/invalidation/failure tests, fail-closed launch tests, uncertain-state
  tests, and source-architecture checks that reject raw API client sockets.
- Stabilized a process-tree regression assertion by tracking descendant process
  identities instead of raw process handles, avoiding false failures after PID
  reuse while preserving the production PID/start-time safety rule.
- Retained Java 17 bytecode targeting, `-Xlint:all -Werror`, deterministic
  archives, extracted-release execution, extracted-source rebuild/retest,
  real-window GUI smoke, patch reproduction, and SHA-256 publication checks.

## 1.0.13 - 2026-08-03

### Quiet IBC command-server monitoring

- Removed the two-second steady-state TCP probe of the IBC command port. IBC
  logs every accepted and closed command channel, so that probe continuously
  filled otherwise healthy profile logs with `ControlFrom`, accepted-connection,
  and closing-channel messages.
- Added explicit command-server lifecycle tracking from IBC output: starting,
  ready/listening, accepted command, failed/disabled, and shutdown states.
- Changed periodic profile refresh to consume the cached lifecycle state without
  opening a command connection.
- Changed Stop, Restart, Pause, reconnect, and enable-API operations to send the
  real command directly rather than opening a preliminary health-check socket.
- Added one reattachment-only fallback probe for an already-running process when
  its command-server startup line is outside the bounded log tail. The result is
  cached and never repeated by normal status refreshes.
- Preserved the one-time occupied-port launch preflight before credentials are
  loaded or a password-bearing runtime configuration is created.
- Clarified the Overview and Commands tabs so command readiness is described as
  reported by IBC rather than inferred from recurring TCP probes.
- Kept the separate IB API TCP-readiness check unchanged. It remains explicitly
  documented as a socket check, not an IB API handshake or account validation.

### Validation

- Increased the suite to **477 automated tests** and **5,484 assertions** across
  **101 production** and **24 test** Java source files.
- Added lifecycle-parser, controller, reattachment, direct-command, and source-
  architecture regressions that fail if recurring command-port polling or
  command pre-probing is reintroduced.
- Retained Java 17 bytecode targeting, `-Xlint:all -Werror`, deterministic
  archives, extracted-release execution, extracted-source rebuild/retest,
  real-window GUI smoke, and SHA-256 publication checks.

## 1.0.12 - 2026-08-03

### Windows portable-runtime packaging correction

- Fixed the `_Release_windows.zip` and installed EXE packages starting IBC
  Manager successfully but failing when a profile was started with
  `Could not locate Java runtime: ...\runtime\bin\java.exe`.
- Overrode jpackage's default jlink options for both the portable app image and
  EXE installer so native Java commands are retained. The package still strips
  debug data, headers, and manual pages.
- Added an immediate post-jpackage gate that requires a nonempty portable
  `runtime\bin\java.exe` before the installer or Windows release ZIP is built.
- Strengthened Windows release-ZIP assembly to require the exact nonempty
  portable launcher, versioned application JAR, and Java process launcher rather
  than accepting any file somewhere below `app` or `runtime`.
- Reopen and validate the completed Windows ZIP before atomically activating it.
- Added a negative build-driver self-test proving an app image with a runtime
  directory but no `java.exe` is rejected.
- Hardened detached-relay startup to prefer nonempty `java.exe` and accept
  nonempty `javaw.exe` as a Windows fallback, with an explicit incomplete-runtime
  error when neither exists.

### Validation

- Increased the suite to **474 automated tests** and **5,458 assertions** across
  **101 production** and **24 test** Java source files.
- Added runtime-launcher selection tests and source/package invariants for both
  jpackage invocations, native-command retention, exact portable-runtime
  contents, and missing-runtime rejection.
- Stabilized the existing cross-platform process-tree test by allowing the
  operating system a bounded post-termination reaping interval before asserting
  that captured descendant handles are no longer alive; production termination
  behavior is unchanged.
- Retained Java 17 bytecode targeting, `-Xlint:all -Werror`, deterministic
  archives, extracted-release execution, extracted-source rebuild/retest,
  real-window GUI smoke, and SHA-256 publication checks.

## 1.0.11 - 2026-08-03

### Windows release-gate correction

- Fixed the deterministic Windows failure in **process tree terminator captures
  descendants spawned during shutdown** that stopped both
  `validate-windows.bat` and `package-windows.bat` after 472 successful tests.
- Removed the process-tree test fixture's dependency on a JVM shutdown hook
  being executed after an external process termination request. That behavior is
  not a portable cross-platform test contract.
- Replaced it with a Java-only cooperative shutdown fixture. The root receives a
  signal through its standard-input pipe, starts a child, publishes the child PID
  through an atomic file move, and remains alive while the production terminator
  discovers and stops the new descendant.
- Added a test-only `ManagedProcess` adapter that maps `destroy()` to the
  cooperative signal while retaining real process PID, start-time, descendant,
  wait, exit, and force-termination behavior.
- Added source-architecture checks requiring the cooperative fixture and
  rejecting shutdown-hook-dependent process-termination tests.
- Normalized the tracked default IBC template and retained GPL licence files to
  the LF policy declared by `.gitattributes`, while keeping `.bat` and `.ps1`
  files CRLF. Fresh Git checkouts and source-ZIP builds now reproduce the same
  JAR and release archives byte-for-byte.
- Kept production process termination unchanged: captured processes are still
  verified by PID, start time, and available command fingerprint before graceful
  or forced termination.

### Validation

- Retained **473 automated tests** and increased the logical assertion count to
  **5,431** across **101 production** and **24 test** Java source files.
- Added 30 consecutive targeted stress runs of the corrected dynamic-descendant
  test before the final release gate.
- Retained Java 17 bytecode targeting, `-Xlint:all -Werror`, deterministic
  archives, extracted-release execution, extracted-source rebuild/retest,
  real-window GUI smoke, and SHA-256 publication checks.

## 1.0.10 - 2026-08-03

### Release and repository readiness

- Added GitHub Actions build/test coverage for Ubuntu and Windows plus a real
  Swing-window smoke job under Xvfb.
- Added `SECURITY.md`, `CONTRIBUTING.md`, `RELEASE_CHECKLIST.md`, issue-template,
  `.gitignore`, and `.gitattributes` files for a public GitHub repository.
- Kept the production runtime dependency-free beyond the Java 17 standard
  modules and retained official IBC as a separate, unmodified installation.

### Filesystem and persistence hardening

- Added bounded, strict-encoding reads for profiles, managed/existing IBC
  configurations, runtime configurations, credentials, process identities,
  relay descriptors, diagnostic log tails, installer metadata, and transaction
  metadata.
- Added no-follow filesystem operations for application-owned directories and
  control files, rejecting symbolic-link substitution instead of following it.
- Hardened atomic writes, backups, runtime cleanup, profile discovery, IBC
  validation, installation activation, log handling, and single-instance locks.
- Added transactional profile deletion with durable PREPARED/COMMITTED
  tombstones, credential rollback, startup recovery, and transaction-name/profile
  identity validation. An incomplete rollback now retains its PREPARED tombstone
  and staged data so startup recovery can finish safely instead of deleting the
  only recoverable copy.
- Extended profile-save rollback so profile, managed configuration, and
  credential state are restored together after late failures.

### Process, command, and runtime safety

- Added process fingerprints to persisted PID/start-time identities and remove
  stale or malformed identities before reattachment. Fingerprints use stable
  executable-command metadata; if the operating system temporarily stops exposing
  command metadata during process exit, matching falls back to PID and start time,
  while an explicit non-empty mismatch still fails closed.
- Hardened exact process-tree termination against PID reuse and descendants
  created while the root process is shutting down.
- Added one total IBC command deadline, bounded line/response sizes and counts,
  strict host/port validation, preserved partial-response semantics, and
  line-boundary command-result parsing that avoids quadratic response scanning.
- Added cross-profile collision detection across both API and IBC command-port
  roles.
- Restricted process-relay descriptor deletion to the exact private descriptor
  filename format and rejected symbolic or oversized descriptors.

### Logging, diagnostics, and input handling

- Bounded application-log, process-log, live-output, and dashboard-log memory
  use while preserving the requested 60-second routine disk-write cadence. A
  failed final process-log write remains retryable instead of permanently closing
  the buffer with unwritten data.
- Expanded redaction for commented IBC assignments, command-line forms,
  structured values, URL query values, overlapping exact secrets, and bounded
  exception stack traces.
- Prevented invalid command-line and profile paths from reflecting control
  characters back into console or GUI diagnostics.
- Added bounded and no-follow diagnostic reads and retained deterministic,
  redacted diagnostic bundle behavior.
- Hardened command execution so interruption or stream/setup failure terminates
  the spawned subprocess and does not leave an unmanaged child running.

### Validation

- Expanded the suite to **473 automated tests with 5,428 assertions** across
  **101 production** and **24 test** Java source files.
- Added release-audit regression coverage for file bounds, malformed UTF-8,
  symbolic links, rollback, stale transactions, process identity, process-tree
  races, command-server limits, port collisions, redaction, relay safety, and
  invalid-path handling.
- Retained Java 17 bytecode targeting, `-Xlint:all -Werror`, packaged-JAR smoke,
  real-window GUI smoke, deterministic release/source archives, extracted-source
  rebuild/retest, archive integrity checks, and SHA-256 publication gates.

## 1.0.9 - 2026-08-02

### Windows release archive

- Changed `IBC_Manager_1.0.9_Release_windows.zip` to contain only the two
  requested Windows payloads: `IBC Manager-1.0.9.exe` at the archive root and
  the complete portable `IBC Manager` application-image folder.
- Removed the normal JAR release, batch launchers, documentation, notices,
  licences, and checksum file from the Windows-only archive. Those remain in
  `IBC_Manager_1.0.9_Release.zip` and the source package.
- Added fail-closed validation for the app-image launcher, `app` payload,
  bundled `runtime`, exact installer name, duplicate installer output, completed
  ZIP readability, and any file outside the installer/portable-folder layout.
- Retained temporary ZIP creation and activation only after successful
  validation, preventing a partial final archive from being published.

### Documentation image

- Added the supplied main-window screenshot as `images/GUI.png` and displayed it
  directly below the README title.
- Included the `images` directory in the normal release ZIP and source ZIP, with
  build and architecture checks that reject a missing, empty, or unreferenced
  screenshot.

### Runtime status presentation

- Added a prominent text-backed traffic-light panel for the selected profile and
  matching colored state icons in the profile list.
- Green is reserved for the directly observed **API TCP open** condition. Yellow
  covers startup, authentication, logged-in-with-API-closed, paused, stopping,
  and unknown states. Red covers stopped and error states.
- Kept explicit state text and accessible descriptions so status does not depend
  on color alone. The API-TCP caveat remains visible: an open socket is not proof
  of an IB API handshake, account match, permissions, or trading readiness.

### Buffered log writes

- Changed manager-owned application logs and IBC/TWS console logs to batch
  routine disk writes at a 60-second cadence.
- Added a detached Java process relay that forwards IBC/TWS output live to the
  GUI while buffering disk output. It continues draining and writing logs if the
  GUI exits while the managed process remains active.
- Added final partial-batch commits when the manager logger closes or the managed
  process exits. A hard crash or power loss can still lose up to approximately
  60 seconds of manager-owned buffered log data.
- Added bounded relay descriptors, owner-only permission hardening, malformed and
  truncated descriptor rejection, failed-write cleanup, live-output queue caps,
  UTF-8 handling, and scheduled-write retry behavior.

### Validation

- Hardened timeout handling in the platform-neutral command executor so a
  process stream closed during forced timeout termination preserves any
  already captured output instead of surfacing a spurious `Stream closed`
  failure.
- Expanded the suite to **430 automated tests with 5,222 assertions**.
- Added executable tests for 60-second production cadence constants, no early
  disk write, live pre-flush output, scheduled and final commits, detached relay
  survival, relay descriptor round trips and cleanup, traffic-light mappings,
  accessibility text, README image packaging, and the exact Windows ZIP layout.
- Retained strict Java 17 bytecode targeting, warning-free compilation,
  deterministic release/source artifacts, JAR smoke tests, real Swing GUI smoke,
  extracted-source rebuild/retest, and archive-integrity checks.

## 1.0.8 - 2026-08-02

### Windows packaging

- Extended `package-windows.bat` so a successful `jpackage` installer build
  also creates `dist\IBC_Manager_1.0.8_Release_windows.zip`.
- The Windows archive starts from the exact normal release staging directory,
  so it retains the versioned JAR, prerequisite-aware launcher,
  documentation, licences, and third-party notices. It then adds the generated
  `IBC Manager-1.0.8.exe` installer and an installer `SHA256SUMS.txt` file.
- Kept the internal archive root identical to the normal release root:
  `IBC_Manager_1.0.8`. The additional `_windows` suffix applies only to the ZIP
  filename, as requested.
- Added fail-closed checks for a missing or empty app-image launcher, missing,
  empty, duplicate, or unexpectedly named installer, incomplete normal release
  staging tree, missing archive contents, and incorrect embedded checksum data.
- The archive is written to a temporary file in `dist` and replaces the final
  filename only after validation, avoiding a half-written Windows release ZIP.
- The batch script now verifies that the expected Windows release ZIP exists
  after the build driver reports success and returns a nonzero exit status when
  ZIP assembly fails.

### Validation

- Extended the executable build-driver self-test to create a synthetic
  app-image launcher and EXE installer, assemble the Windows release ZIP,
  inspect its installer/JAR/launcher entries, verify its checksum file, and
  reject ambiguous multiple-installer output.
- Added packaging invariants that enforce post-EXE ZIP creation order, exact
  `_Release_windows.zip` naming, use of the current application version, and
  retention of the normal release contents.
- Final release result: 425 tests, 5,113 assertions, zero failures, zero skipped.

## 1.0.7 - 2026-08-02

### Fixed

- Made the full automated suite portable to Windows by replacing Bash and Unix
  device-file subprocess fixtures with a small Java fixture launched through the
  active JDK and an absolute compiled-test classpath.
- Corrected unsafe-path validation tests so Windows may reject an
  unrepresentable path at `Path.of(...)` without turning the expected safety
  result into a test failure.
- Hardened `SingleInstanceLock` with a JVM-local path reservation in addition
  to the operating-system file lock. A same-JVM duplicate attempt is now
  rejected before opening a second channel, so closing that failed channel
  cannot release the first process-wide native lock on POSIX systems.
- Corrected the single-instance lock regression test for Windows mandatory file
  locking. It now proves exclusion with a separate Java process, verifies that
  a same-JVM overlap cannot release the cross-process lock, inspects owner
  metadata only after release, and proves cross-process reacquisition.
- Corrected Windows `package-windows.bat` validation failures in command
  execution, timeout/output-capping, process launching, process-tree
  termination, process reattachment, path safety, and single-instance locking.

### Changed

- Added an explicit profile-specific confirmation dialog to normal **Stop**.
  Cancel is selected by default, and confirming still invokes IBC Manager's
  existing graceful-stop path rather than force termination.
- Expanded the Stop prompt to explain that API and market-data connectivity will
  end after the graceful shutdown request.

### Validation

- Added a static architecture gate that rejects executable Bash and `/dev/zero`
  dependencies in the Java test tree.
- Added real GUI smoke coverage for opening and cancelling **Confirm stop**.
- Added profile-model assertions for the Stop prompt title, action label,
  warning severity, graceful-shutdown wording, and connectivity impact.
- Final release result: 424 tests, 5,082 assertions, zero failures, zero skipped.

## 1.0.6 - 2026-08-02

### Windows source build reliability

- Removed Apache Ant from every supplied Windows run, build, test, validation,
  and packaging path. The 1.0.5 bootstrap could not install Ant when every
  configured 1.10.17 archive URL returned HTTP 404.
- Added a dependency-free JDK-native source driver at
  `src/build/java/io/github/ibcmanager/build/BuildProject.java`.
- `run.bat` now builds a missing source JAR with the selected Java 17+ JDK;
  `test.bat`, `build.bat`, `validate-windows.bat`, and
  `package-windows.bat` use the same driver.
- Build and test modes now require only a complete Java 17+ JDK. Package mode
  additionally requires `jpackage` and WiX Toolset 3.x.
- Retained `build.xml` as an optional compatibility wrapper for developers who
  already have Apache Ant installed. Every Ant target delegates to the same
  JDK-native driver and source-tree lock; no bundled script downloads or
  requires Ant.
- The native build driver enforces `--release 17`, `-Xlint:all`, and `-Werror`,
  runs tests and JAR smoke checks, creates release/source ZIPs with fixed entry
  timestamps, and filters generated artifacts from the source ZIP.
- Added an operating-system file lock keyed to the canonical source-tree path.
  Concurrent mutating builds now wait instead of allowing one `clean` or compile
  operation to delete another build's classes; waiting is bounded to 15 minutes
  and covered by an executable lock-serialization self-test.
- Added regression checks proving that no Ant URL, installer, environment
  variable, or executable remains in the Windows bootstrap path.

### Session-action presentation

- Made **Stop** use the same larger 112 x 38 minimum action-button treatment as
  Start, Restart, and Pause.
- Added a distinct red Stop accent, bold typography, stable minimum/preferred
  dimensions, tooltip, and accessibility metadata.
- Kept normal Stop as a direct graceful stop; the existing separate force-stop
  confirmation remains unchanged.
- Expanded model, source-wiring, and real-window GUI smoke coverage to verify all
  four session buttons remain visible, uncompressed, and visually distinct.

### Validation

- Expanded the suite to **423 automated tests with 4,986 assertions**.
- Retained strict Java 17 bytecode targeting, warning-free compilation,
  packaged-JAR smoke tests, real Swing GUI smoke testing, optional Ant build
  compatibility, and extracted release/source validation.

## 1.0.5 - 2026-08-02

### Windows build bootstrap

- Attempted to address `package-windows.bat` failing when Apache Ant 1.10.17
  was absent by adding more download locations. Windows validation later showed
  that every configured 1.10.17 archive URL returned HTTP 404; version 1.0.6
  replaces this unsuccessful approach with the JDK-native build driver.
- Added ordered Apache download fallbacks: the configured `dlcdn` mirror, the
  ASF release repository, `downloads.apache.org`, and the Apache archive.
- Separated archive and checksum fallback lists. The SHA-512 published by the
  server must match the checksum pinned in IBC Manager, and the downloaded
  archive must match that value before extraction.
- Added regression checks for URL priority, fallback coverage, checksum-source
  separation, and the pinned Ant 1.10.17 checksum.

### Official IBC installer

- Added **Install IBC 3.24.1 from GitHub...** beside installation discovery in
  the profile editor.
- The user must explicitly confirm before any download or installation.
- Downloads the tested official Windows asset `IBCWin-3.24.1.zip` and installs
  it transactionally in the conventional `C:\IBC` directory.
- Reuses an existing valid IBC 3.24.1 installation without downloading it.
- Refuses to overwrite a non-empty invalid destination or a symbolic-link
  destination.
- Restricts download and redirect hosts to GitHub's HTTPS release hosts, uses
  bounded timeouts and archive-size limits, and reports the downloaded SHA-256.
- Pins and enforces the SHA-256 published by GitHub for the supported official
  `IBCWin-3.24.1.zip` asset before any archive entry is extracted.
- Added safe ZIP extraction with traversal, absolute-path, reserved-name,
  ambiguous-path, duplicate-path, entry-count, per-entry, and total-expansion
  protections.
- Validates the version file, `config.ini`, licence, `scripts\StartIBC.bat`, and
  the expected IBC program classes before activation.
- Added cancellation, staging cleanup, destination-race detection, and clear
  access-denied guidance. The downloaded IBC code and JAR are not modified.

### Profile editor

- Added dedicated profile-tab support for IBC's `SecondFactorDevice=` setting.
  The value is normalized into one canonical profile setting and removed from
  the generic advanced-settings table to avoid conflicting controls.
- Fixed the clipped **Detect common installations...** button by preserving the
  preferred dimensions of both installation actions and placing the long
  profile form in a vertical scroll pane rather than compressing its rows.
- Added GUI smoke checks that instantiate the real profile dialog and verify the
  installation buttons, `SecondFactorDevice` field, scroll pane, and actual
  button dimensions.

### Session-action confirmation and presentation

- Added a profile-specific confirmation before every manual **Start**,
  **Restart**, and **Pause** action. Each dialog identifies the selected profile,
  application, trading mode, and API port; live starts carry an explicit
  live-trading warning, while restart/pause explain their connectivity impact.
- Made **Cancel** the initially selected option so an accidental Enter key does
  not dispatch the operation. Cancelling leaves the selected runtime unchanged.
- Made Start, Restart, and Pause larger, bold, accessible, and individually
  accented. Moved session controls to their own toolbar row so Swing cannot
  compress them when the window is at its supported minimum size.
- Extended source-wiring, model, accessibility, and real-window GUI regression
  coverage. The GUI smoke opens and cancels all three modal dialogs, verifies
  actual rendered button dimensions and accents, and confirms no runtime starts
  or changes state after cancellation.

### Validation

- Expanded the suite to **419 automated tests with 5,538 assertions**.
- Added transactional installer, hostile-archive, cancellation, destination-race,
  URI-policy, configuration-plumbing, layout, and bootstrap regression tests.
- Retained strict Java 17 compilation with `-Xlint:all -Werror`, packaged-JAR
  smoke tests, real Swing GUI smoke testing, and archive/source rebuild gates.

## 1.0.4 - 2026-08-02

### Windows launcher fix

- Fixed a startup-blocking Windows PowerShell collision with the read-only
  `HOME` automatic variable. PowerShell variable names are case-insensitive, so
  the former `Home` function parameters and `home` loop variables failed before
  Java discovery could reach the prerequisite prompt.
- Renamed all executable bootstrap variables to explicit non-reserved names:
  `CandidateHome`, `JavaHome`, `candidateHome`, and `antHome`. Object properties
  such as `JavaInfo.Home` remain unchanged because property names do not bind a
  PowerShell variable.
- Added an executable source invariant that rejects a future direct use of the
  protected automatic-variable name. Bootstrap invariants now execute quietly on
  every normal run as well as through `validate-windows.bat -SelfTest`.
- Added regression coverage for the renamed parameters, both candidate loops,
  normal-entry self-test ordering, CRLF/no-BOM packaging, and versioned launch
  artifacts.
- Tightened source archive filtering so local validation `.log` files and
  temporary files cannot be included in a release source ZIP.

## 1.0.3 - 2026-08-02

### Reviewed and retained from the supplied fixes

- Prevented profile-list and dashboard reads from waiting on long-running controller
  operations by publishing immutable profile and status snapshots through volatile fields.
- Preserved explicit PAUSED and application-exited log states instead of allowing socket
  probes to overwrite them with misleading STARTING/API states.
- Reset stale log-state hints when an IBC session restart is requested.
- Corrected Windows Task Scheduler `/TR` construction so the complete quoted action is
  delivered to `schtasks.exe` as one argument.
- Added raw managed-configuration validation before save.
- Redacted password values when duplicate settings are disabled.

### Additional fixes found during review

- Made `run.bat` usable directly from the source archive. When the versioned JAR is absent,
  it now asks permission for the Java 17 JDK/Ant prerequisites, builds and smoke-tests the
  JAR, and then starts the application.
- Added a console-visible JAR preflight before `javaw` is launched. Corrupt JARs and Java
  bytecode/runtime mismatches now fail with an actionable message instead of disappearing
  after the console detaches.
- Closed remaining persistent-secret paths: every active password occurrence is checked,
  including an earlier duplicate followed by a blank value, case variants, comments, and
  malformed/raw lines. Diagnostic rendering redacts every occurrence, and manager-owned
  persistence sanitizes imported values conservatively.
- Blocked case-variant sensitive or profile-controlled keys in advanced profile settings
  and repeated the plaintext-secret gate after profile settings are applied.
- Made plaintext credentials in the managed raw editor a blocking validation error rather
  than an overridable warning that would inevitably fail during save.
- Corrected PAUSE lifecycle handling. IBC's PAUSE command shuts down Gateway/TWS while
  preserving the resumable session; the expected process exit now remains PAUSED instead
  of being reported as an unexpected-error exit.

### Validation

- Expanded regression coverage for source-launch self-build, JAR preflight, hidden duplicate
  credentials, comment/raw-line credentials, case-insensitive sanitization, managed-editor
  validation, and process exit after PAUSE.
- Retained strict Java 17 compilation with `-Xlint:all` and `-Werror`, deterministic repeated
  test runs, packaged-JAR smoke tests, real Swing-window smoke testing, archive extraction,
  clean source rebuild, class-file checks, dependency analysis, and checksum verification.

## 1.0.2 - 2026-08-02

### Fixed

- Fixed the Windows PowerShell prerequisite bootstrap failing immediately with
  `Cannot bind argument to parameter 'List' because it is an empty collection`.
- Marked both intentionally empty generic candidate collections with
  `AllowEmptyCollection`, covering the Java runtime discovery path used by
  `run.bat`, `build.bat`, `test.bat`, `validate-windows.bat`, and
  `package-windows.bat`.

### Validation

- Added a PowerShell self-test that invokes the exact formerly failing binding
  path with empty `List[string]` and `HashSet[string]` instances and verifies
  duplicate suppression.
- Added release regression assertions requiring both collection parameters to
  retain their strong types and explicit empty-collection allowance.
## 1.0.1 - 2026-08-02

### Fixed

- Prevented `run.bat` from launching the Java 17-targeted application with Java
  8 selected from `PATH`, which produced `UnsupportedClassVersionError` or an
  equivalent Java exception.
- Changed every Windows run/build/test/validation/package entry point to use an
  explicitly detected and version-verified Java executable.

### Added

- Shared `scripts\bootstrap.bat` and
  `scripts\ensure-prerequisites.ps1` prerequisite bootstrap.
- Explicit `[Y/N]` permission prompt before any prerequisite installation.
- Private per-user Microsoft OpenJDK 17 installation with official SHA-256
  verification; existing Java installations and persistent environment
  variables remain untouched.
- Private per-user Apache Ant 1.10.17 installation with a pinned SHA-512 and
  verification against Apache's published checksum.
- Exact-ID WiX Toolset 3.x installation through Windows Package Manager for
  `.exe` packaging, with clear UAC and missing-WinGet handling.
- Separate dependency modes for running, building, testing, validation, and
  packaging.
- Serialized and staged managed-tool installation with rollback of a prior
  managed version on activation failure; obsolete-backup cleanup cannot roll
  back an already verified replacement.
- Bounded prerequisite downloads and fail-closed handling for tool paths that
  cannot be represented by the active Windows console code page.
- Root-level `run.bat`, `build.bat`, `test.bat`, and
  `validate-windows.bat` convenience launchers.
- PowerShell parser/escaping self-tests and extensive static regression tests for
  consent, checksums, Java 8 handling, isolation, cleanup, and selected-tool use.

## 1.0.0 packaging refresh - 2026-08-02

- Added a root-level `package-windows.bat` launcher for discoverability.
- Retained the canonical `scripts\package-windows.bat` Windows packaging script.
- Added release tests that verify both scripts and their required build gates.

## 1.0.0 - 2026-08-01

Initial Windows-first release.

### Added

- Java 17 Swing GUI for managing multiple independent IBC profiles.
- First-run profile wizard and common-installation discovery.
- Structured IBC configuration editor plus a raw, comment-preserving editor.
- Manual, external-config, and Windows DPAPI-backed credential modes.
- Per-profile process ownership, exact PID/start-time reattachment, and isolated process-tree shutdown.
- IBC command-server controls for stop, restart, pause, reconnect, and API enablement.
- Live process, command-port, API-port, state, and log monitoring.
- Redacted diagnostic ZIP export.
- Interactive, least-privilege Windows Task Scheduler integration.
- Portable JAR build and Windows `jpackage` scripts.
- Dependency-free automated test harness, GUI smoke test, and release validation scripts.

### Security and reliability controls

- Persistent manager-owned configuration files reject plaintext passwords.
- Encrypted credentials use Windows DPAPI scoped to the current Windows user and profile ID.
- Temporary runtime configuration is owner-restricted, redacted, and deleted after authentication progresses.
- Application logs do not propagate to an unredacted JVM console handler.
- Launching delegates to the official IBC `StartIBC.bat`; credentials are never passed in command-line arguments.
- Profile validation prevents duplicate command/API ports and conflicting settings directories.
- Launch preflight refuses occupied API or command ports before decrypting a password or creating a runtime config.
- Generated configs, launch scripts, task names, and task arguments reject line-breaking and NUL control characters.
- DPAPI plaintext transport is UTF-8/Base64-safe for non-ASCII passwords and does not print plaintext to standard output.
- UTF-8 log tailing preserves multibyte characters split across incremental reads.
- Force stop is an explicit confirmed GUI action and remains scoped to the selected PID/start-time process tree.
