# Changelog

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
