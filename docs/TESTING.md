# Testing and release gates

IBC Manager follows a fail-closed release process modelled on the BouncyBot
project: compile warnings are errors, tests are deterministic and isolated, and
release artifacts are created only after all gates pass.

## Automated gates

### Compiler gate

All production and test sources compile with:

```text
javac --release 17 -Xlint:all -Werror
```

This prevents accidental use of APIs newer than Java 17 and treats compiler
warnings as release failures.

### Test suites

The dependency-free runner covers:

- profile serialization and randomized round trips;
- line-preserving IBC config parsing, mutation, duplicate detection, and random
  exact render round trips;
- profile and multi-profile validation;
- redaction, UTF-8/Base64-safe DPAPI command construction, command
  timeouts/output caps, and file/directory permission hardening;
- atomic storage and recovery;
- IBC command protocol, log parser, occupied-port preflight, process identity,
  launch-script quoting, configuration-injection rejection, incremental UTF-8
  log tailing, 60-second application/process disk batching, detached live-output
  relay survival, descriptor validation, and final partial-batch commits;
- complete controller state transitions, failure cleanup, exact process-tree
  termination, and runtime credential-file lifecycle;
- diagnostics redaction, unique naming, permissions, and failure cleanup;
- Windows Task Scheduler quoting, interactive logon, least privilege, and delay
  validation;
- common installation discovery;
- official IBC 3.24.1 release-coordinate policy, HTTPS redirect restrictions,
  pinned official archive SHA-256, transactional installation, hostile ZIP
  handling, cancellation, destination races, activation cleanup, and
  existing-install reuse;
- dedicated `SecondFactorDevice` profile ownership, canonicalization, blank-value
  removal, and generated-config plumbing;
- real profile-dialog construction, installation-action sizing, vertical scrolling,
  and field visibility;
- profile-specific Start/Stop/Restart/Pause prompt content, live warning
  severity, confirmation wiring, cancellation behavior, and the shared
  emphasized action-button accessibility, accent, and rendered-dimension policy;
- app bootstrap, paths, locking, CLI smoke mode, redacted 60-second
  application-log batching, final close commit, and service lifecycle;
- Swing table models, constrained editors, command availability, explicit
  force-stop controls, EDT helpers, and secret result lifecycle;
- Java 8 version parsing/rejection and Java 17+ selection;
- Windows PowerShell binding of initially empty Java candidate collections;
- explicit prerequisite-installation consent and decline behavior;
- private Java installation paths and absence of persistent environment changes;
- Microsoft SHA-256 enforcement before JDK archive extraction;
- complete removal of Apache Ant from the Windows prerequisite and launcher
  path, with the in-repository JDK-native build driver used instead;
- per-source-tree operating-system build locking, visible contention, bounded
  timeout behavior, and executable lock serialization/release;
- exact WinGet WiX package identity and package-mode-only dependency handling;
- named-mutex serialization, staged activation, rollback, non-destructive
  obsolete-backup cleanup, and temporary-file cleanup;
- bounded network downloads and fail-closed console-path encoding;
- verified executable use by every Windows launcher;
- PowerShell lexical balance, Windows PowerShell 5.1-compatible syntax policy,
  parser/escaping self-test presence, CRLF line endings, and release inclusion;
- detached process-output relay lifecycle, owner-restricted descriptor
  round trips, live-before-disk output, 60-second production cadence, final
  process-exit commit, and continued persistence after the manager process exits;
- traffic-light status mapping, explicit non-color text, API-handshake caveat,
  profile-list rendering, and real-window visibility;
- source architecture, README image/release inclusion, Windows archive-only
  installer/portable-image layout, dependency boundaries, Java class-file
  version, licensing, absence of global desktop automation, and deterministic
  logical assertion accounting.

### Prerequisite bootstrap self-test

`scripts\validate-windows.bat` invokes:

```bat
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass ^
  -File scripts\ensure-prerequisites.ps1 -SelfTest
```

This runs deterministic parsing and escaping checks without downloading or
installing anything. It covers Java 8/17/21 version formats, WiX output, Windows
argument quoting, batch metacharacter escaping, prerequisite-mode descriptions,
and the initially empty Java-candidate collection binding path.

### GUI smoke gate

`GuiSmokeRunner` starts the real `MainFrame`, loads a valid profile, verifies the
profile editor and the emphasized Start/Stop/Restart/Pause layout, opens and
cancels the Start, Stop, Restart, and Pause confirmation dialogs, proves that
cancellation did not start or change the process, lets the runtime refresh, and
closes through the normal window path.
On Linux CI it runs under Xvfb; on Windows it runs in an interactive desktop
session.

### Packaged JAR gate

The built JAR is executed with `--version` and `--headless-smoke` using a clean
isolated data directory. The archive is inspected for its manifest, production
classes, and `default-config.ini` resource.

### Static release checks

- No external Java dependencies.
- No wildcard imports, trailing whitespace, tabs, unresolved work markers, or
  generated binaries inside source directories.
- No `java.awt.Robot`, global window automation, or all-process enumeration.
- No username/password launcher arguments.
- No bundled IBC binary.
- Windows scripts use CRLF and no UTF-8 BOM.
- The prerequisite bootstrap contains no executable variable named `Home` in any
  capitalization, and its normal entry path runs deterministic invariants before
  Java discovery.
- Release/source archives contain the exact prerequisite bootstrap files.
- The source `run.bat` contains an on-demand JAR build path, while the release
  path remains runtime-only; both preflight the JAR with the selected Java before
  starting `javaw`.
- Persistent-secret tests cover earlier duplicates hidden by a later blank,
  comments, raw lines, key-case variants, diagnostic rendering, and managed-save
  rejection.
- PAUSE tests cover both the transition while the process remains alive and the
  expected process exit that must remain PAUSED.
- SHA-256 checksums are generated for final artifacts.

### Release security and rollback audit gate

The 1.0.10 audit suite exercises bounded and strict-encoding reads, symbolic-link
substitution, atomic-write targets, profile/config/credential rollback, durable
profile-deletion tombstones, incomplete-rollback retention and startup recovery,
process fingerprints, exit-time metadata loss, PID-reuse protection, descendants
created during root shutdown, one-deadline IBC command responses, response/line
caps, line-boundary parsing, cross-role port collisions, log-memory caps, retry of
a failed final process-log write, interrupted subprocess cleanup, relay-descriptor
path restrictions, expanded secret redaction, and invalid path diagnostics.
Failure cases assert that unrelated files, processes, profiles, and credentials
remain unchanged.

### Cross-platform subprocess and lock regression gate

Tests that exercise command execution, process lifecycle, process trees, and
reattachment launch a small Java fixture through the active JDK and an absolute
compiled-test classpath. They do not require Bash, Unix device files, cmd.exe,
or PowerShell. Unsafe-path validation accepts the host file system rejecting an
unrepresentable path before profile validation, and the single-instance lock
test reads lock metadata only after release so it remains valid under Windows
mandatory file-lock behavior. The lock regression also proves that a rejected
same-JVM overlap does not release the cross-process native lock.

The dynamic-descendant test is also platform-neutral. A root Java fixture waits
for a standard-input signal, creates a child after termination begins, publishes
the child PID atomically, and stays alive long enough for the production
terminator to discover the child. It deliberately does not use a JVM shutdown
hook because external process termination does not provide portable shutdown-hook
semantics across Windows and Unix-like systems. A source-architecture assertion
rejects reintroduction of that non-portable test pattern.

## 1.0.13 automated result

Validated on 2026-08-03 in the release build environment:

- 101 production source files and 24 test source files;
- 477 automated test cases;
- 5,484 assertions;
- zero failed or skipped cases;
- Java 17 bytecode target verified as class-file major version 61;
- real `MainFrame` GUI smoke test under Xvfb;
- packaged JAR `--version` and isolated `--headless-smoke`;
- release and source archive extraction/rebuild checks.

The 1.0.13 regression gate proves that periodic controller refreshes do not
connect to the IBC command server. It checks lifecycle-state parsing, direct
command dispatch without a preliminary socket probe, one cached fallback probe
when reattaching without historical startup output, and continued one-time
occupied-port preflight before launch. Architecture tests reject any future
command-port probe inside the steady-state refresh or command-send paths.

## 1.0.12 automated result

Validated on 2026-08-03 in the release build environment:

- 101 production source files and 24 test source files;
- 474 automated test cases;
- 5,458 assertions;
- zero failed or skipped cases;
- Java 17 bytecode target verified as class-file major version 61;
- real `MainFrame` GUI smoke test under Xvfb;
- packaged JAR `--version` and isolated `--headless-smoke`;
- release and source archive extraction/rebuild checks.

The Windows-package regression gate now checks the actual runtime requirement
that failed in 1.0.11 packages. Both jpackage invocations must override the
default jlink options without `--strip-native-commands`, and the portable app
image must contain a nonempty `runtime\bin\java.exe`, and the completed
`IBC_Manager_<version>_Release_windows.zip` is reopened and checked for that exact
entry. Synthetic package assembly also proves that an app image with a runtime
directory but no Java process launcher is rejected.

The runtime launcher has a separate cross-platform test that rejects an empty or
missing Java launcher, prefers `java.exe` on Windows, and accepts nonempty
`javaw.exe` as a fallback.

## 1.0.11 automated result

Validated on 2026-08-03 in the release build environment:

- 101 production source files and 24 test source files;
- 473 automated test cases;
- 5,431 assertions;
- zero failed or skipped cases;
- 30 consecutive targeted runs of the cooperative-shutdown process-tree test;
- Java 17 bytecode target verified as class-file major version 61;
- real `MainFrame` GUI smoke test under Xvfb;
- packaged JAR `--version` and isolated `--headless-smoke`;
- release and source archive extraction/rebuild checks.

The build-driver self-test additionally assembles a synthetic Windows release
archive from fake jpackage outputs. It verifies the exact
`IBC_Manager_<version>_Release_windows.zip` filename, a root-level versioned
installer, the complete portable app-image folder with application and runtime
payloads, absence of unrelated normal-release files, completed-ZIP readability,
and rejection of multiple direct EXE installers.

The compiler used for cross-platform validation is OpenJDK 21 with
`--release 17`; the runtime compatibility target remains Java 17. Exact
commands, assertion count, artifact hashes, and limitations are recorded in
`TEST_REPORT.md`.

## Running locally on Windows

The scripts detect prerequisites and request permission before installation:

```bat
test.bat
build.bat
validate-windows.bat
```

`validate-windows.bat` runs the PowerShell self-test, complete Java tests, JAR
smokes, and the real GUI smoke.

## Running with a preinstalled JDK

```text
java -Dfile.encoding=UTF-8 src/build/java/io/github/ibcmanager/build/BuildProject.java clean test
java -Dfile.encoding=UTF-8 src/build/java/io/github/ibcmanager/build/BuildProject.java \
  self-test clean test jar smoke dist
```

The retained `build.xml` can still be used by developers who already have Ant.
It delegates every target to `BuildProject.java`, so the same strict gates and
source-tree lock apply; no supplied Windows script requires or downloads Ant.

GUI smoke on Linux:

```text
xvfb-run -a java -cp build/classes:build/test-classes:src/main/resources \
  io.github.ibcmanager.tests.GuiSmokeRunner
```

## Tests that require a Windows validation machine

The Linux build environment cannot prove Windows-native behavior. Before a
public/live release, execute `validate-windows.bat` and complete
`WINDOWS_VALIDATION_CHECKLIST.md` on a clean Windows 11 machine. Test at least:

- Java 8 as the only Java on `PATH`;
- installation refusal and approval paths;
- private JDK download and checksum verification;
- offline/proxy/download-corruption failure behavior;
- WinGet/WiX installation and UAC behavior;
- the packaged `.exe`/app image;
- Windows DPAPI;
- `schtasks.exe`;
- source build, test, validation, and package operation on a machine with no Ant
  installed;
- official IBC 3.24.1 download, transactional installation, reuse, cancellation,
  access-denied handling, and non-overwrite behavior for `C:\IBC`;
- profile-dialog layout at common Windows DPI scales and
  `SecondFactorDevice` persistence;
- the larger/distinct Start/Stop/Restart/Pause row and cancellation of all four
  confirmation dialogs at common Windows DPI scales;
- green/yellow/red profile status presentation and API-handshake caveat;
- live log display before the 60-second manager-owned disk commit, periodic
  commit timing, final close/process-exit commits, and no duplicate records;
- offline paper IB Gateway/TWS;
- a real manual second-factor login.

No automated test substitutes for confirming the actual IBKR login dialog,
account identity, and API handshake in paper trading.
