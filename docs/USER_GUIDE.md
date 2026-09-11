# User guide

## 1. Start IBC Manager and check prerequisites

Extract the binary release and run `run.bat`. Do not invoke a random `java.exe`
from `PATH`; Java 8 cannot run this application.

The launcher searches configured overrides, the manager's private tools folder,
`JAVA_HOME`, every `PATH` entry, and common Windows JDK locations. It verifies
the executable's reported major version before launching. Java 17 or newer is
accepted.

When no compatible Java runtime is found, a console prompt lists the missing
requirement and asks:

```text
Proceed with prerequisite installation? [Y/N]
```

- **Y** downloads Microsoft OpenJDK 17, verifies the published SHA-256 checksum,
  and installs it under `%LOCALAPPDATA%\IBCManager\tools\microsoft-jdk-17`.
- **N** exits with no installation or persistent environment change.

The private Java installation does not uninstall Java 8 or modify machine/user
`PATH` or `JAVA_HOME`. An existing compatible Java installation is reused
without prompting.

The release ZIP already contains the versioned JAR. The source ZIP does not.
When `run.bat` is started from the source ZIP, it requests permission for a Java
17 JDK when one is unavailable, runs the included dependency-free
`BuildProject.java` driver, builds and smoke-tests the JAR, and then starts the
GUI. Apache Ant is not required or downloaded. Both paths run a console-visible
`--version` preflight before detaching into `javaw`.

## 2. Install or select IBC

IBC Manager can use a compatible official IBC installation at version 3.24.2
or newer. The profile editor offers two actions:

- **Detect common installations...** scans conventional Windows locations.
- **Install latest IBC from GitHub...** resolves GitHub's latest published full
  IBC release and installs its official Windows asset in `C:\IBC` after explicit
  confirmation.

The installer:

- queries only `api.github.com/repos/IbcAlpha/IBC/releases/latest`;
- rejects draft, prerelease, malformed, non-numeric, and below-floor releases;
- selects exactly `IBCWin-<resolved-version>.zip`;
- verifies the downloaded byte count and SHA-256 against GitHub's release-asset
  metadata as well as the transfer-computed values;
- reuses an existing valid installation only when it is already the resolved
  latest version;
- never silently overwrites a different or invalid non-empty `C:\IBC` directory;
- downloads only over HTTPS from GitHub's release infrastructure;
- extracts into a temporary staging tree;
- rejects unsafe or oversized ZIP content;
- validates the external and embedded IBC versions, required classes,
  `StartIBC.bat` switches, and any helper scripts referenced by the launcher;
- reports the resolved version and downloaded SHA-256 digest;
- removes temporary files after success, failure, or cancellation.

Creating `C:\IBC` may require administrator rights. When Windows denies access,
close the manager and start it as administrator, then retry. The installer does
not install IB Gateway/TWS and does not modify IBC's source or JAR.

Separately install an **offline** TWS or IB Gateway build. Keep each automated
instance's TWS settings in a separate directory.

## 3. Create a profile

On first launch, the profile editor opens. For additional profiles, choose
**File > New profile**.

Set:

- a unique profile name;
- Gateway or TWS;
- live or paper mode;
- the numeric offline application version;
- IBC, application-root, and settings paths;
- unique API and IBC command ports;
- a loopback command bind address, normally `127.0.0.1`;
- the IBKR username and credential mode;
- the Java directory that IBC should use, or leave it blank for verified
  resolution from the offline installation. Java 17 is the minimum; IBC Manager
  enforces a higher class-file requirement when a future selected IBC release
  needs one;
- `SecondFactorDevice`, when IBC must choose one exact registered second-factor
  device;
- the wrapper action and internal relogin policy for second-factor timeout;
- whether IBC should force the API port through its configuration UI. This is
  off by default for new profiles.

The official `StartIBC.bat` launcher expands these paths through `cmd.exe`.
IBC Manager therefore rejects `"`, `%`, `!`, `&`, `|`, `<`, `>`, `^`, `(`, and
`)` in any path passed to that launcher. The validation error identifies the
exact character and recommends simple locations such as `C:\IBC`, `C:\Jts`,
and `C:\IBKRSettings`. This deliberately means that a location under
`C:\Program Files (x86)` is unsupported; move or reinstall that component to a
batch-safe path rather than bypassing the validation.

Leave **Second-factor device** blank to use IBC's normal/default device-selection
behavior. IBC Manager does not generate or submit TOTP codes.

The structured IBC settings tab stores only explicit overrides. Blank values
leave the base/default value unchanged. `SecondFactorDevice` is intentionally
shown only on the Profile tab so two controls cannot define conflicting values.

The raw **Managed config** editor and structured **Profile Edit** dialog are
synchronized in both directions. Saving raw config updates the matching Profile
fields (username, mode, minimize option, API-port override, command port, bind
address, 2FA relogin policy, and second-factor device) plus advanced overrides.
Opening Profile Edit first reloads those values from the managed file. Saving
Profile Edit then canonicalizes known settings while preserving comments,
unknown/future properties, and unrelated raw values. A raw change that would
create an invalid profile or cross-profile port conflict is rejected atomically.

The effective semantic map is always loaded with one full-file Java Properties
parser operation, matching IBC's own configuration loader. The second scanner
exists only to preserve comments, ordering, and line endings. If malformed raw
syntax causes those two interpretations to disagree, IBC Manager blocks a
persistent rewrite and offers explicit canonicalization of IBC's authoritative
settings. Canonicalization deliberately replaces the complete document with a
plain canonical property list, so all comments, ordering, and custom formatting
are removed. Review the result before saving.

Raw imported path-like values with a single backslash produce a warning because
Java Properties consumes that backslash as an escape. For example,
`IbDir=C:\Jts` is interpreted as `C:Jts`; use `IbDir=C:\\Jts` for the literal
Windows path. The warning identifies the setting but never displays password or
credential content.

## 4. Validate

Select the profile and choose **Validate**. Resolve every error. Review warnings,
especially non-loopback command-server access, remote command sources, or live
mode.

## 5. Start and authenticate

Choose **Start**. IBC Manager first displays a profile-specific confirmation
showing the application, trading mode, and API port. Live profiles include an
explicit live-trading warning. **Cancel** is the default and performs no launch.

After confirmation, and before credentials are decrypted, the manager forces a
fresh passive read of the local operating-system TCP listener table and verifies
that the configured API and command ports are not already occupied. Startup is
blocked when that listener table cannot be inspected. The dashboard then
distinguishes process startup, login handling, second-factor waiting, IBC login
completion, and API-listener detection. A prominent status panel and profile
list use green, yellow, or red indicators together with explicit text. On Windows, green means the operating system reports a listener on the
configured API port whose PID belongs to the managed process tree. It still does
not claim an IB API handshake or account validation. A listener owned by another
process remains yellow and uncertain.

Complete second-factor authentication manually. `SecondFactorDevice` only tells
IBC which registered device to select when multiple devices are presented.

During IBC-managed recovery, the dashboard uses a yellow **Restarting** state for
an automatic restart, cold restart, login-dialog timeout recovery, or
second-factor timeout recovery. A normal `ClosedownAt` completion ends in
**Stopped**, not **Error**. Child-JVM error text remains yellow while the
`StartIBC.bat` supervisor decides whether to launch a replacement; it becomes
red only when the supervisor itself exits without completing a recovery.

## 6. Verify the API client

A green **API listener detected** state is not an IB API handshake. IBC Manager
never opens a raw API connection for monitoring. Confirm in BouncyBot or another
API client that:

- the expected account is returned;
- the API handshake completes;
- permissions and read-only status are correct;
- the client ID and API port match the intended profile.

## 7. Control a running profile

The larger, bold, individually accented Start, Stop, Restart, and Pause buttons
are on a separate session row so they remain visible and are not compressed by
profile configuration actions.

IBC Manager determines command-server readiness from IBC's own lifecycle log
messages. It does not open and close a monitoring connection on every two-second
status refresh. When reattaching to a process whose startup line is outside the
bounded log tail, one passive operating-system listener-table observation is
permitted and its result is cached.

API-port monitoring is passive as well. Windows uses the trusted system
`netstat.exe`; Linux reads `/proc/net/tcp` and `/proc/net/tcp6`. Normal monitoring
does not connect to either service port. If listener inspection temporarily
fails, the dashboard remains yellow/uncertain rather than claiming the API is
ready or closed.

When IBC has reported both command-server readiness and `Login has completed`,
the Commands tab can request:

- controlled session restart;
- pause;
- market-data reconnect;
- account reconnect;
- API enablement for TWS profiles. IBC's `ENABLEAPI` command is not offered for
  Gateway profiles.

IBC's **Pause** command shuts down TWS/Gateway while preserving resumable
session state. After IBC accepts the command the dashboard shows **Pausing**.
It changes to **Paused** only after the official StartIBC wrapper reports
`IBC is paused` and exits. An accepted command followed by an unconfirmed or
failed exit is reported as an error.

Manual **Stop**, **Restart**, and **Pause** each display an additional
confirmation before the operation is sent. The prompt identifies the profile and explains the
expected connectivity impact; cancelling leaves the running session unchanged.

After confirmation, **Stop** requests IBC's normal stop and waits for the
configured timeout. It never silently becomes a hard kill. When the timeout
expires, the profile remains in **Stopping** and the process continues running.
Use **Tools > Force stop selected profile...** only after a separate confirmation
when hard termination is actually required. Force stop is limited to the
selected PID, process creation time, and descendants.

Each real Stop, Restart, Pause, reconnect, or enable-API request creates one
normal IBC command connection, so one corresponding accepted/closed channel
sequence in IBC output is expected. Repeating command-channel sequences every two seconds while idle indicate an
older IBC Manager release is still running. Likewise, repeated IB Gateway lines
stating `Client disconnected before version was sent` and `API client version is
missing` indicate a pre-1.0.14 API-port monitor or another application is still
opening raw TCP connections without completing an IB API handshake.

## 8. IBC compatibility and restart behavior

IBC Manager 1.0.21 resolves the latest published official IBC release when the
GUI installer is used. Version 3.24.2 is the reviewed compatibility floor and
retained configuration reference, not a download pin. A downloaded or manually
selected IBC folder is accepted only when its numeric version is at or above the
floor and its external version file, embedded JAR version, required classes,
Windows launcher switches, and referenced helper capabilities agree.

The runtime `config.ini` passed to `StartIBC.bat` remains available at one stable,
access-restricted path for the complete wrapper lifetime. This is required
because StartIBC may launch more than one IBC JVM after login timeout, 2FA
recovery, auto-restart, or cold restart. The detached relay scrubs and removes
the file when the wrapper exits, including when the Manager GUI is no longer
running.

Generated IBC configuration uses Java Properties encoding and escaping. Literal
backslashes, Unicode usernames, passwords, and second-factor device names are
preserved according to the same full-file `Properties.load(InputStream)`
semantics used by compatible IBC releases. Generated bytes are reloaded through the JDK parser
and compared with the intended property map before they are written.

The command client accepts the normal IBC `OK ...` and `ERROR ...` replies and
also exact bare `OK` or `ERROR` lines. Preliminary `OK ... in progress` remains
non-final; a later error still overrides it.

**Restart** is intentionally implemented as graceful Stop followed by a fresh
Start. IBC Manager does not send IBC's native `RESTART` command because that
command can fall back to changing the application's persistent auto-restart
schedule.

Profiles that share the same offline TWS/Gateway program directory are
serialized only during StartIBC's initial executable-rename phase. Once the
wrapper reports its IBC launch command, the startup lock is released and the
sessions continue independently.

## 9. Logs and diagnostics

The Logs tab receives the selected profile's IBC/TWS console output live through
a detached relay. Manager-owned application and profile-process log files are
buffered in memory and committed to disk once every 60 seconds during normal
operation. Closing the manager logger or termination of the managed process
performs a final immediate commit for the partial interval.

This policy reduces routine disk writes, but a hard crash or power loss can lose
up to approximately 60 seconds of manager-owned buffered records. Log files
written independently by IBC, TWS, or IB Gateway are not controlled by this
setting. Diagnostic export creates a timestamped ZIP containing redacted
configuration, runtime status, versions, and the recent records already present
on disk. Review the ZIP before sharing it.

## 10. Automatic startup

Mark profiles for automatic startup, then select **Tools > Install startup task**
from the packaged application. The task starts one minute after interactive
user logon, at limited privilege, with `--autostart`.

The task intentionally requires an interactive desktop because IBKR and IBC can
present visible authentication and warning dialogs.

## 11. Multiple profiles

Every simultaneously running profile requires:

- a different IBC command-server port;
- a different TWS/Gateway API port;
- no cross-role collision where one profile's API port equals another profile's
  IBC command port;
- a different settings directory;
- normally a separate IB Gateway/TWS process and API client ID.

The validator blocks conflicting values before launch.

Deleting a stopped profile is transactional. IBC Manager stages its profile and
runtime directory, removes the stored credential, and either completes the
operation or restores the prior state. An interrupted PREPARED deletion is
restored at the next startup; a durable COMMITTED deletion is completed. This
recovery does not replace normal backups of user-selected external IBC/TWS
settings directories, which are not owned or deleted by IBC Manager.

## 12. Build, test, and package the source

The source archive contains root-level launchers:

```bat
run.bat
test.bat
build.bat
validate-windows.bat
package-windows.bat
```

`run.bat` builds the missing JAR on demand. Build, test, and validation require a
Java 17+ JDK. The bootstrap can install Microsoft OpenJDK 17 privately under
`%LOCALAPPDATA%\IBCManager\tools` after explicit permission.

Since version 1.0.6, the repository uses the JDK-native `BuildProject.java` driver for
all supplied Windows build scripts. Apache Ant is not a prerequisite, is not
downloaded, and cannot block the build because an Ant mirror or archive URL is
unavailable. `build.xml` remains as an optional compatibility path for users who
already have Ant installed.

Packaging additionally requires `jpackage` and WiX Toolset 3.x. When WiX is
missing, the script asks permission and invokes Windows Package Manager with the
exact `WiXToolset.WiXToolset` package identity. This may trigger Windows UAC.
When WinGet is unavailable, packaging stops with a manual installation
instruction.

After the app image and EXE installer are created, version 1.0.21 also produces:

```text
dist\IBC_Manager_1.0.21_Release_windows.zip
```

This Windows-only archive contains exactly:

```text
IBC Manager-1.0.21.exe
IBC Manager\
    IBC Manager.exe
    app\...
    runtime\...
```

It does not duplicate the normal release JAR, scripts, documentation, notices,
or checksum files. ZIP creation is rejected if the installer is missing, empty,
duplicated, or not named for the current version, or if the portable image is
missing its launcher, versioned application JAR, or
`runtime\bin\java.exe`. The package script overrides jpackage's default jlink
options so the portable and installed runtimes retain the Java process launcher
used by IBC Manager's detached output relay.

The scripts never uninstall an existing Java release or permanently rewrite the
system environment. Verified tool paths are passed through a temporary batch
environment file that is removed immediately after import.
