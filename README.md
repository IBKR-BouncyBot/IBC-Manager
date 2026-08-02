# IBC Manager

IBC Manager is a Windows-first graphical front end for configuring, launching,
monitoring, and controlling multiple IBC-managed Trader Workstation or IB
Gateway sessions.

It deliberately **does not reimplement or modify IBC**. The manager delegates
startup to the official `scripts\StartIBC.bat` from a separately installed IBC
release and uses IBC's command server for supported session controls. This keeps
IBC's mature Swing-dialog automation intact.

- Version: **1.0.8**
- IBC compatibility baseline: **3.24.1**
- Runtime target: **Java 17 or newer**

## Main capabilities

- Profile dashboard for IB Gateway and TWS, live or paper mode.
- Multiple isolated profiles with separate API ports, IBC command ports,
  settings directories, credentials, logs, and process identities.
- Common Windows installation discovery for IBC and offline TWS/Gateway builds.
- Optional download and transactional installation of the official Windows IBC
  3.24.1 release from GitHub into the conventional `C:\IBC` directory.
- Structured editor for common IBC settings, including a dedicated
  `SecondFactorDevice` profile field.
- Raw `config.ini` editor that preserves comments, ordering, unknown future
  settings, blank values, and line endings while blocking persistent password
  assignments, including hidden duplicates and assignment-shaped comment/raw
  lines.
- Profile validation before save and before launch, including occupied-port
  preflight before any password-bearing runtime file is created.
- Start, graceful stop, explicitly confirmed force-isolated process cleanup,
  restart, pause, reconnect-market-data, reconnect-account, and enable-API
  operations. Start, Stop, Restart, and Pause use the same larger bold action
  style with separate accents. Manual Start, Stop, Restart, and Pause each
  require a contextual confirmation with Cancel selected by default. PAUSE is tracked
  through the expected Gateway/TWS exit and remains distinguishable from an
  unexpected crash.
- Process reattachment using exact PID and process start time.
- Live IBC log tailing and state classification.
- TCP checks for the IBC command port and configured IB API port.
- Redacted diagnostic bundles.
- Interactive Windows Task Scheduler startup task.
- Windows DPAPI credential storage, manual-password mode, and external-config
  mode.
- No TOTP generation or submission.

## Important boundaries

IBC Manager reports whether the configured API TCP port accepts a connection.
That does **not** prove that an IB API handshake completed, that the intended
account is authenticated, or that trading is permitted. A trading application
must still perform its own API handshake and account verification.

IBC Manager does not bundle IBC, TWS, or IB Gateway. The profile editor can
install the tested official IBC 3.24.1 Windows release, but offline TWS or IB
Gateway must still be installed separately. Use the **offline** TWS/IB Gateway
installer expected by IBC, not an installation layout that changes underneath
the launcher.

The IBC installer targets the tested compatibility baseline rather than silently
selecting an untested future release. Updating `IBC_BASELINE` requires a new IBC
Manager build and validation cycle.

## Credential modes

### Manual

IBC Manager supplies the configured username and leaves the password blank for
manual entry in the IBKR login window.

### Windows encrypted

The password is encrypted with Windows DPAPI for the current Windows user and a
profile-specific entropy value. It is not stored in `profile.properties` or the
persistent managed `config.ini`. The DPAPI bridge uses UTF-8 bytes and Base64
transport so non-ASCII passwords do not depend on a console code page.

IBC still needs a plaintext password during login. Therefore, the manager
creates an owner-restricted temporary runtime `config.ini`, starts IBC, and
removes that file once logs or socket state indicate authentication has
progressed. The file is also scrubbed during stop, failed start, stale-state
cleanup, and safe manager shutdown. Full compromise of the logged-in Windows
user can still expose both the application and its credentials.

### Existing IBC config

The manager launches using an existing `config.ini` and does not modify or copy
credentials from it. Security of that file remains the user's responsibility.

## Quick start on Windows

The release archive includes a prerequisite-aware launcher. Extract the archive
into a normal directory and run:

```bat
run.bat
```

The launcher checks the actual Java version before starting the JAR. Java 8 is
not compatible with IBC Manager. When Java 17 or newer is unavailable, the
launcher displays exactly what is missing and asks:

```text
Proceed with prerequisite installation? [Y/N]
```

After an explicit **Y**, it downloads Microsoft OpenJDK 17, verifies Microsoft's
published SHA-256 checksum, and installs it only under:

```text
%LOCALAPPDATA%\IBCManager\tools\microsoft-jdk-17
```

It does not uninstall Java 8, change the machine/user `PATH`, or change
`JAVA_HOME`. The selected Java 17 executable is used only for that invocation.
Answering **N** exits without installing anything.

If the GUI does not appear, open PowerShell in the extracted directory and run
`./run.bat` or `.\run.bat` so prerequisite and JAR-preflight diagnostics remain
visible. Do not run either ZIP directly without extracting it first.

IBC Manager additionally requires:

1. IBC 3.24.1 containing `IBC.jar` and `scripts\StartIBC.bat`.
2. A separate offline TWS or IB Gateway installation.
3. An interactive Windows desktop for IBKR authentication dialogs.

Advanced users who already have Java 17+ can also run:

```bat
java -jar IBC-Manager-1.0.8.jar
```

## Creating a profile and installing IBC

On first launch, the profile editor opens automatically. At the top of the
**Profile** tab:

- **Detect common installations...** searches conventional Windows locations.
- **Install IBC 3.24.1 from GitHub...** asks for confirmation, downloads the
  official Windows release asset, validates it, and installs it in `C:\IBC`.

The installer never overwrites a non-empty invalid `C:\IBC` directory. It uses a
staging directory, enforces compressed/extracted size and entry-count limits,
rejects unsafe ZIP paths, validates the version file, required files,
`StartIBC.bat`, and expected classes in `IBC.jar`, and only then activates the
installation. The complete archive must also match the SHA-256 published for the
supported official GitHub asset and pinned in this IBC Manager release. A
pre-existing valid IBC 3.24.1 installation is reused without a download. Because
`C:\IBC` is at the drive root, Windows can require IBC Manager to be started as
administrator to create it.

The installer downloads IBC only. It does not install IB Gateway or TWS and does
not alter the downloaded IBC code or JAR.

Set or verify:

- the IBC directory;
- the TWS/Gateway root directory, commonly `C:\Jts`;
- the TWS settings directory;
- the numeric offline application version;
- unique API and IBC command ports;
- the exact IBC `SecondFactorDevice` value when more than one registered device
  is presented, or leave it blank for IBC's normal device-selection behavior.

Start with a paper profile. Validate it, launch it, complete second-factor
authentication manually, and verify the expected account and API connection in
your client application before creating a live profile.

Application data is stored by default under:

```text
%LOCALAPPDATA%\IBCManager
```

Use `--data-dir <directory>` for an isolated or portable data directory.

## Command-line options

```text
--autostart                 Start enabled profiles marked for automatic startup
--data-dir <directory>      Override the application data directory
--headless-smoke            Validate bootstrap and stored profiles without a GUI
--version                   Print application and IBC baseline versions
```

## Release versus source archive

The **release ZIP** contains the prebuilt versioned JAR and is intended for
normal use. `run.bat` starts that JAR after prerequisite verification.

The **source ZIP** contains all Java sources, tests, documentation, `build.xml`,
and build/package scripts. It intentionally contains no generated JAR or class
files. Running `run.bat` from an extracted source tree builds and smoke-tests the
missing JAR before launching the GUI.

The Windows packaging script additionally creates
`IBC_Manager_1.0.8_Release_windows.zip`. It uses the same versioned release
directory and contents as the normal release ZIP, then adds the generated
Windows installer EXE and an installer `SHA256SUMS.txt` file.

## Building and testing

The source archive provides:

```bat
run.bat
test.bat
build.bat
validate-windows.bat
package-windows.bat
```

The Windows scripts require only a Java 17+ runtime for the release package and
a complete Java 17+ JDK for source builds. If a compatible Java installation is
missing, they ask permission before installing Microsoft OpenJDK 17 under:

```text
%LOCALAPPDATA%\IBCManager\tools\microsoft-jdk-17
```

Since version 1.0.6, Apache Ant is not part of the Windows prerequisite or build path.
Source builds use the dependency-free `BuildProject.java` driver included in
the repository, so the Apache Ant archive HTTP 404 failure cannot block
`run.bat`, `test.bat`, `build.bat`, `validate-windows.bat`, or
`package-windows.bat`.

The equivalent cross-platform JDK-native build command is:

```text
java -Dfile.encoding=UTF-8 src/build/java/io/github/ibcmanager/build/BuildProject.java \
  self-test clean test jar smoke dist
```

`build.xml` is retained as an optional compatibility wrapper for developers who
already have Apache Ant installed. It delegates every target to the same
JDK-native driver, including its source-tree lock; the supplied Windows scripts
do not use or download Ant.

The native build driver compiles with `--release 17`, `-Xlint:all`, and
`-Werror`, creates deterministic JAR/ZIP entries, runs the complete test suite,
and smoke-tests the packaged JAR. Mutating build targets are serialized with an
operating-system lock derived from the canonical source-tree path, so concurrent
`clean`, compile, test, and distribution commands cannot delete each other's
output. Version 1.0.8 includes **425 automated test cases with 5,113 assertions**,
plus a real-window Swing GUI smoke gate. See
[TEST_REPORT.md](TEST_REPORT.md) and [docs/TESTING.md](docs/TESTING.md).

## Windows standalone package

Run from the extracted **source archive root**:

```bat
package-windows.bat
```

The script checks and, only after permission, installs the required tools:

- a Java 17+ JDK with `jpackage` in the private per-user tools directory;
- WiX Toolset 3.x through Windows Package Manager when WiX is absent.

WiX installation can display a Windows elevation prompt. Packaging runs the
complete test suite, builds the JAR, performs headless and GUI smoke tests, and
invokes `jpackage` to create a self-contained application image and an `.exe`
installer. After the installer is created, the script validates that there is
exactly one versioned installer in the Windows output directory and creates:

```text
dist\IBC_Manager_1.0.8_Release_windows.zip
```

That archive contains the normal release files plus the generated
`IBC Manager-1.0.8.exe` installer and its SHA-256 checksum. The script fails
rather than creating an ambiguous release if the installer is missing, empty,
misnamed, or duplicated. The packaged application includes a Java runtime but
still uses a separately installed IBC and offline TWS/IB Gateway.

## Documentation

- [User guide](docs/USER_GUIDE.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Security model](docs/SECURITY.md)
- [Testing and release gates](docs/TESTING.md)
- [Windows validation checklist](docs/WINDOWS_VALIDATION_CHECKLIST.md)

## License

IBC Manager is licensed under GPLv3. See `LICENSE.txt` and `NOTICE.txt`. The
retained IBC template and notices are under `third_party/ibc-3.24.1`. The IBC
downloader retrieves the official IBC release at runtime; IBC is not included in
IBC Manager's release ZIP.
