# Architecture

## Design objective

IBC Manager is a supervisory GUI around the existing IBC automation engine. It
must not replace IBC's direct Java Swing component handling with global window
titles, screen coordinates, or simulated keyboard focus. IBC remains a separate,
unmodified runtime dependency.

## Components

```text
Windows launchers
  ├─ prerequisite discovery and version verification
  ├─ explicit installation consent
  ├─ private Java tool store
  └─ exact selected-tool environment
       │
Swing UI
  ├─ profile editor and validation
  ├─ IBC discovery and official-release installer
  ├─ managed/raw config editor
  ├─ runtime dashboard, session-action confirmations, and log view
  ├─ command controls and diagnostics
  └─ startup-task controls
       │
Application services
  ├─ profile repository
  ├─ managed configuration service
  ├─ credential store
  ├─ official IBC installer service
  ├─ runtime registry
  ├─ diagnostics service
  └─ task scheduler service
       │
Per-profile runtime controller
  ├─ runtime config lease
  ├─ official StartIBC.bat launch script
  ├─ exact process identity
  ├─ detached live-output and 60-second disk-buffer relay
  ├─ log tailer/state parser
  ├─ command-server client
  └─ API/command TCP probes
       │
Separately installed official IBC + offline TWS/IB Gateway
```

`ProfileSessionAction` centralizes presentation for Start, Stop, Restart, and
Pause, and the confirmation policy for manual Start, Stop, Restart, and Pause.
`MainFrame` cannot dispatch those four confirmed actions without first invoking
the policy. Confirmed normal Stop remains a graceful-stop request; force stop
remains a separate confirmed recovery action. Automatic startup is an explicitly
configured path and does not display modal dialogs.

## Windows prerequisite bootstrap

Every root launcher delegates to a canonical script under `scripts`. The
canonical scripts call `scripts\bootstrap.bat` with one explicit mode: `Run`,
`Build`, `Test`, `Validate`, or `Package`. The run launcher uses `Run` when the
release JAR exists. In a source tree without a JAR it deliberately switches to
`Build`, creates and smoke-tests the JAR with the bootstrap-selected JDK and the
repository's `BuildProject.java` source driver, then continues with the same
verified Java installation.

`scripts\ensure-prerequisites.ps1` then:

1. discovers all plausible Java homes instead of trusting the first executable
   on `PATH`;
2. runs `java -version` and enforces Java 17 or newer;
3. verifies JDK tools, `jpackage`, and WiX only when the selected mode needs
   them;
4. requests explicit user permission before installing anything;
5. installs Java into a current-user private tools directory with checksum
   verification;
6. serializes concurrent installers with a named mutex;
7. stages replacements and restores the previous managed version on failure;
8. writes only verified executable paths to a temporary environment file;
9. deletes that environment file after the calling batch script imports it.

The build, test, validation, and package scripts invoke the repository's
dependency-free source driver through the selected Java executable:

```text
java src/build/java/io/github/ibcmanager/build/BuildProject.java <targets>
```

The driver compiles with Java 17 bytecode targeting and warnings-as-errors,
runs the test and smoke gates, creates the versioned JAR, and produces
deterministic release/source ZIP entries. Every invocation containing a mutating
target acquires an operating-system file lock whose identity is derived from the
canonical source-tree path. The lock lives in the system temporary directory,
outside directories removed by `clean`; concurrent builds visibly wait for up to
15 minutes rather than deleting one another's classes or archives. `self-test`
and `version` can run without the main lock, and the self-test uses a separate
temporary root to exercise actual lock exclusion. Apache Ant is not downloaded
or required. `build.xml` remains only as an optional compatibility wrapper for
developers who already have Ant installed; every target delegates to the same
driver and therefore participates in the same project lock.

For Windows packaging, both jpackage invocations override the default jlink
options so native Java commands are retained while debug data, headers, and
manual pages remain stripped. The detached relay starts a child JVM and therefore
requires a nonempty `runtime\bin\java.exe`; the script checks that exact file
immediately after app-image creation.

After `jpackage` successfully creates the Windows app image and EXE installer,
`package-windows.bat` invokes the build driver's `windows-release-zip` target.
That target writes:

```text
dist\IBC_Manager_<version>_Release_windows.zip
```

The Windows archive has no versioned wrapper directory and contains only the
single versioned installer at its root plus the complete portable `IBC Manager`
app-image directory. It intentionally does not duplicate the normal JAR release,
documentation, launch scripts, notices, or checksum files. ZIP assembly fails
closed when the app-image launcher, versioned application JAR,
`runtime/bin/java.exe`, or installer is missing or empty; when more than one installer is present; when the
installer name does not match the current version; when any unrelated file is
staged; or when the completed ZIP cannot be reopened and validated.

Java 8 can remain installed for other applications. No persistent `PATH` or
`JAVA_HOME` mutation is performed.

At application level, `SingleInstanceLock` combines an owner-hardened operating-
system file lock with a normalized-path reservation held in the current JVM.
Cross-process exclusion comes from the native lock; the JVM reservation ensures
that a duplicate acquisition in the same process fails before opening and
closing a second channel, which could otherwise disturb process-wide native
locking semantics on POSIX systems.

## Official IBC installation

The profile editor can invoke `IbcInstallerService` to install the tested IBC
compatibility baseline. This service does not modify IBC and is separate from the
Java prerequisite bootstrap.

```text
User confirmation
  │
Official GitHub release URI
  │ HTTPS + restricted redirect hosts + bounded download
Temporary ZIP
  │ transfer SHA-256 + pinned official SHA-256
Unique staging directory beside C:\IBC
  │ safe extraction and validation
Atomic move where supported
  │
C:\IBC
```

The installer:

- targets the explicit `IBC_BASELINE` and corresponding `IBCWin-<version>.zip`;
- requires the complete archive to match the SHA-256 published by GitHub for the
  supported asset and pinned in the application;
- reuses a valid existing baseline installation without a network request;
- rejects non-empty invalid or symbolic-link destinations;
- enforces download, entry-count, per-entry, and total-expansion limits;
- rejects path traversal, absolute paths, Windows reserved/ambiguous names, and
  case-insensitive duplicate paths;
- accepts either root-level official files or one enclosing top-level folder;
- requires exactly one IBC installation tree;
- validates `IBC.jar`, `version`, `config.ini`, `LICENSE.txt`, and
  `scripts\StartIBC.bat`;
- verifies the expected IBC TWS and Gateway classes are present in the JAR;
- checks the destination again immediately before activation;
- cleans staging and temporary files after success, failure, or cancellation.

The manager's release ZIP still does not bundle `IBC.jar`. The runtime download
is an explicit user action and retrieves the official release unchanged.

## Session-action presentation

Profile/configuration controls and session controls use separate toolbar rows.
This avoids `JToolBar` shrinking the primary actions when the supported minimum
window width is used. Start, Stop, Restart, and Pause have stable
minimum/preferred dimensions, bold labels, individual accent borders/text,
tooltips, and accessible descriptions.

For Start, Stop, Restart, and Pause, the confirmation prompt is built from an
immutable profile snapshot and includes the selected target, trading mode, and
API port. Cancel is the default option, and only an explicit action-specific
choice reaches the runtime controller. Confirmed Stop requests a graceful stop;
the destructive force-stop path has its own separate confirmation.

## Status presentation

`StatusIndicator` maps runtime states to a text-backed traffic-light status.
Color is supplementary: the selected profile always has an explicit headline
and detail message, and each profile-list entry includes its state in text.

- Green is reserved for the directly observed condition that the configured API
  TCP socket accepts connections.
- Yellow covers transitional, waiting, paused, unknown, and logged-in states
  where the API socket is not open.
- Red covers stopped and error states.

The green state does not imply an IB API handshake, account match, permission
check, or trading readiness.

## Profile isolation

Every profile has a UUID and independent:

- persistent profile file;
- managed `config.ini`;
- optional DPAPI credential file;
- runtime directory and launch script;
- PID/start-time identity file;
- IBC log;
- API port;
- IBC command-server port;
- TWS settings directory.

Set-level validation rejects duplicate profile names, API ports, command ports,
and settings directories where they would cause unsafe interference.

## Configuration layers

1. The retained IBC template or a user-selected base config is parsed into a
   line-preserving document.
2. The managed config layer applies non-secret profile values and explicit GUI
   overrides while retaining comments and unknown keys. Before manager-owned
   persistence, all active sensitive occurrences are inspected, including
   duplicates, case variants, comments, and unparsed lines.
3. At launch, a runtime copy is produced. Encrypted credentials are inserted
   only into that temporary copy.
4. The official IBC launcher receives `/Config:<runtime-file>` and the selected
   installation paths.

Profile-controlled keys cannot be overridden by the structured settings table:
username, password, trading mode, window minimization, API port, command port,
bind address, legacy settings path, and `SecondFactorDevice`.

`SecondFactorDevice` is edited on the Profile tab. Loading is case-insensitive;
saving removes case-variant duplicates, trims the value, and writes the canonical
key. Blank removes the profile override and results in an empty IBC setting.

## Buffered logging

The manager-owned application logger formats and redacts records in memory.
`PeriodicFileHandler` commits the pending batch to the rotating application log
once every 60 seconds during normal operation and commits the final partial
batch when the logger closes.

Profile process output is handled by `BufferedProcessRelay`, a small detached
Java process launched as the exact managed process root. It starts the official
IBC launch command as its child, continuously drains merged stdout/stderr, and:

1. forwards the bytes to the GUI over a pipe for immediate display and state
   parsing;
2. buffers the same bytes in memory;
3. appends one batch to the profile log every 60 seconds;
4. performs a final commit when the child exits.

The relay remains alive when the GUI exits, so IBC/TWS output continues to be
drained and periodically persisted. Its owner-only descriptor is created in the
profile runtime directory and deleted before the child command is started. A
reattached manager cannot recover the old live pipe and therefore follows the
60-second disk log instead.

Disk-write failures leave the pending batch in memory for the next interval or
final close attempt. A hard crash or power loss can still lose the current
partial interval. Logging performed independently by IBC, TWS, or IB Gateway is
outside this mechanism.

## Persistent-state transactions and bounded control files

Application-owned state is treated as bounded, untrusted input even though it is
stored under the current user's data directory. `BoundedFileReader` performs
strict character decoding, enforces a caller-specific byte limit while reading,
and opens files without following symbolic links. `SecureFileOperations`
protects directory creation, file classification, moves, and recursive cleanup
from link substitution.

`AtomicFileWriter` writes through a same-directory, forced temporary file and
activates it with an atomic replacement when the file system supports it. Profile
save captures the prior profile, managed configuration, and encrypted credential
state and restores all three after a late failure.

Profile deletion uses a private tombstone directory under `.deletions`. A
`PREPARED` transaction restores staged profile/runtime directories after an
interrupted deletion; a `COMMITTED` transaction completes credential removal and
cleanup. The tombstone directory name must contain the same profile UUID as its
bounded metadata, preventing one transaction from targeting another profile.

Control-file limits are explicit for profiles, IBC configurations, credentials,
process identities, relay descriptors, installer metadata, diagnostic log tails,
and deletion metadata. Malformed, oversized, symbolic, or unexpected file types
fail closed.

## Runtime state

A controller combines:

- exact process liveness;
- IBC log messages;
- IBC command-port availability;
- API TCP-port availability.

Possible states include validating, starting, waiting for login, waiting for
second factor, logged in with API closed, API TCP open, paused, stopping, stopped, and error.

An expected termination reason distinguishes STOP, PAUSE, and an unrequested
exit. PAUSE is retained after TWS/Gateway exits so the resumable session is not
misreported as a crash.

The API socket state is intentionally described as a TCP check only. No account
or protocol handshake is inferred.

## IBC command-server safety

Steady-state command readiness is passive. `IbcLogStateParser` tracks IBC's
command-server starting, ready, accepted-command, failure, and shutdown messages.
`ProfileRuntimeController.refresh()` never opens a command-server connection.
When reattaching to an already-running process whose startup line is outside the
bounded log tail, one fallback TCP probe is allowed and the result is cached.
Launch preflight still checks the configured port once before credentials are
loaded, and explicit commands connect directly without a preliminary probe.

The command client validates host, port, and timeout values, applies one total
operation deadline across connect and response reads, and bounds response lines,
total characters, and line count. It retains IBC's partial-response behavior only
when at least part of a response was received before timeout. Profile-set
validation prevents API/API, command/command, and API/command cross-profile port
collisions.

## Process ownership

The manager stores both PID and process creation instant. Reattachment requires
both values to match, avoiding PID-reuse mistakes. Stop and force-stop operations
act only on the selected process and its descendants. The code never enumerates
and kills every `java.exe`, `tws.exe`, or `ibgateway.exe` process.

## Threading

- Swing components are created and updated on the event-dispatch thread.
- Blocking start, stop, command, diagnostic, Task Scheduler, and IBC download
  work runs through `SwingWorker` or the runtime scheduler.
- The IBC installer publishes progress on the Swing event thread and performs
  network/file work in its worker thread.
- A single daemon scheduler refreshes controllers every two seconds.
- Status listeners use copy-on-write collections and are isolated from one
  another's exceptions.
- Immutable profile/status snapshots are published through volatile references,
  allowing Swing repaint and refresh reads to remain nonblocking while a
  synchronized start or stop operation is in progress.

## Dependency policy

The application and its native build driver use only Java 17 standard-library
APIs. This reduces supply-chain surface and permits an offline source build once
the JDK is available. The separately installed IBC engine remains an external
runtime dependency; the optional installer uses only JDK HTTP, ZIP, JAR,
filesystem, and cryptographic APIs.
