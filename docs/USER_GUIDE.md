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

IBC Manager requires official IBC 3.24.1. The profile editor offers two actions:

- **Detect common installations...** scans conventional Windows locations.
- **Install IBC 3.24.1 from GitHub...** downloads the tested official Windows
  release and installs it in `C:\IBC` after explicit confirmation.

The installer:

- reuses an existing valid IBC 3.24.1 installation;
- never overwrites a non-empty invalid `C:\IBC` directory;
- downloads only over HTTPS from GitHub's release infrastructure;
- verifies the archive against the SHA-256 published for the exact supported
  GitHub release asset and pinned in IBC Manager;
- extracts into a temporary staging tree;
- rejects unsafe or oversized ZIP content;
- validates the version, required files, launcher, and IBC JAR before activation;
- reports the downloaded SHA-256 digest;
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
- `SecondFactorDevice`, when IBC must choose one exact registered second-factor
  device;
- the manual second-factor timeout action.

Leave **Second-factor device** blank to use IBC's normal/default device-selection
behavior. IBC Manager does not generate or submit TOTP codes.

The structured IBC settings tab stores only explicit overrides. Blank values
leave the base/default value unchanged. `SecondFactorDevice` is intentionally
shown only on the Profile tab so two controls cannot define conflicting values.

## 4. Validate

Select the profile and choose **Validate**. Resolve every error. Review warnings,
especially non-loopback command-server access, remote command sources, or live
mode.

## 5. Start and authenticate

Choose **Start**. IBC Manager first displays a profile-specific confirmation
showing the application, trading mode, and API port. Live profiles include an
explicit live-trading warning. **Cancel** is the default and performs no launch.

After confirmation, and before credentials are decrypted, the manager verifies
that the configured API and command ports are not already occupied. The
dashboard then distinguishes process startup, login handling, second-factor
waiting, IBC login completion, and API TCP availability. A prominent status
panel and the profile list use green, yellow, or red indicators together with
explicit text. Green means the configured API TCP socket is open; it still does
not claim that an IB API handshake or account validation completed.

Complete second-factor authentication manually. `SecondFactorDevice` only tells
IBC which registered device to select when multiple devices are presented.

## 6. Verify the API client

A green/open TCP socket is not an IB API handshake. Confirm in BouncyBot or
another API client that:

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
bounded log tail, one fallback TCP probe is permitted and its result is cached.

When the local IBC command server is reported ready, the Commands tab can request:

- session restart;
- pause;
- market-data reconnect;
- account reconnect;
- API enablement for TWS profiles. IBC's `ENABLEAPI` command is not offered for
  Gateway profiles.

IBC's **Pause** command shuts down TWS/Gateway while preserving resumable
session state. During shutdown the dashboard shows PAUSED with the process still
alive; after the expected process exit it remains PAUSED and the Start action is
available. An unrelated process exit remains an error.

Manual **Stop**, **Restart**, and **Pause** each display an additional
confirmation before the operation is sent. The prompt identifies the profile and explains the
expected connectivity impact; cancelling leaves the running session unchanged.

After confirmation, **Stop** first requests IBC's normal stop. After the
configured timeout, only that profile's exact process tree is terminated. For a separately confirmed hard
termination, use **Tools > Force stop selected profile...**. Force stop is also
limited to the selected PID, process creation time, and descendants.

Each real Stop, Restart, Pause, reconnect, or enable-API request creates one
normal IBC command connection, so one corresponding accepted/closed channel
sequence in IBC output is expected. Repeating sequences every two seconds while
idle indicate an older IBC Manager release is still running.

## 8. Logs and diagnostics

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

## 9. Automatic startup

Mark profiles for automatic startup, then select **Tools > Install startup task**
from the packaged application. The task starts one minute after interactive
user logon, at limited privilege, with `--autostart`.

The task intentionally requires an interactive desktop because IBKR and IBC can
present visible authentication and warning dialogs.

## 10. Multiple profiles

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

## 11. Build, test, and package the source

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

After the app image and EXE installer are created, version 1.0.13 also produces:

```text
dist\IBC_Manager_1.0.13_Release_windows.zip
```

This Windows-only archive contains exactly:

```text
IBC Manager-1.0.13.exe
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
