# Upgrade to IBC Manager 2.3.0

## From a working 2.1.0 or 2.2.0 installation

1. Stop all profiles and exit the old Manager.
2. Back up the complete Manager data directory and retain the old application
   folder. The default data directory is `%LOCALAPPDATA%\IBCManager`; include any
   custom data directory selected with `--data-dir`.
3. Extract 2.3.0 to a fresh application folder. Do not merge source/build folders
   or reuse an older compiled JAR.
4. Run the new application under the same Windows account, on the same computer,
   using the same data directory so DPAPI credentials remain accessible.
5. Check the profile's Gateway installation, Java override and managed-runtime
   selection, then start it and complete any required authentication.

**Keep a working JBR 25.0.4-b508.27 / JavaFX 25.0.4 installation unchanged.** The 2.3.0
upgrade does not require disabling the workaround, downloading another runtime,
editing `.vmoptions`, or changing schedules. The repair instructions below apply
only to incompatible or incomplete payloads. Version 2.3.0 checks both exact pins
when reusing managed files; a different JavaFX version is not silently substituted.

Profile format remains **7**. Engine **3.24.2-manager.8** uses its own versioned
cache without overwriting an active older cache. Normal warm restarts remain
supported. The expiry handler also covers the explicit token-expiry dialog during
reconnect after a completed login. It requests a cold restart for fresh authentication;
normal network reconnects do not trigger it. Fresh login and 2FA requirements remain. See [restart modes](RESTART_MODES.md).

Use [TEST_REPORT.md](../TEST_REPORT.md) for the exact executed validation and
[the Windows checklist](WINDOWS_VALIDATION_CHECKLIST.md) for target-machine
acceptance. Preparing a Java/source archive does not execute these Windows checks.

## Manager runtime and profiles

The Windows native package, when built, contains Microsoft Java 25. The Java-release
`run.bat` asks permission to install an isolated Microsoft OpenJDK 25 when required.
It does not delete existing Java installations or convert a manual Gateway JBR/JavaFX
setup into managed ownership automatically.

Profile formats 1–6 remain readable. Saving writes format 7, including the
managed-runtime flag. Format-6 profiles are not rewritten merely because they
were loaded. Versions that do not support format 7 must not write these profiles.

Previously disabled legacy TWS profiles remain disabled. Review their application,
settings directory, ports, credentials and schedules before enabling them. Copy
creates a new configuration identity with unused ports, auto-start off, disabled
status and manual credentials; settings files and runtime state are not copied.
Use a separate settings directory for concurrent instances.

## Rollback

For rollback to the retained working 2.1.0 or 2.2.0 application, stop profiles, close Manager
and restore the corresponding pre-upgrade data backup. Keep the same account,
computer and working runtime files. This 2.3.0 upgrade introduces no profile-format
or managed-runtime ownership change and does not by itself require runtime removal.

Rollback to an older version that does not support format 7 or managed-runtime
ownership is different: with sessions stopped, use the current Manager to disable
managed setup for every owner and save before restoring that older application's
matching data backup. Do not point 2.0.4 at format-7 profiles.

If setup reports an interrupted commit or removal, retain its journals and files.
Reopening Manager can finish or undo a recognized transaction. Unexpected profile,
VM-option or file changes require review; deleting the journal loses that evidence.

## Repairing an incompatible managed runtime only

Early 2.1.0 packages could select a different JBR build or omit
`jdk.jsobject.jar`; releases before 2.3.0 could select another stable JavaFX 25
version. Version 2.3.0 requires the exact pair JBR 25.0.4-b508.27 / JavaFX 25.0.4. Existing non-pinned or incomplete managed payloads are not
silently changed at startup. Only when repairing such an installation: stop all
profiles using it, disable the workaround and Save on every owner, then enable
and Save with the current application. Confirm the pinned JBR 25.0.4-b508.27 and
all eight JavaFX 25.0.4 modules are installed before relying on restart behavior.

Do not copy individual JARs into an owned payload or edit its hash receipt.
Manual runtimes, settings and credentials remain separate. See
[dependency repair](JAVAFX_DEPENDENCY_FIX.md),
[pinned-runtime diagnostics](PINNED_RUNTIME_DIAGNOSTICS.md), and
[managed-runtime ownership](MANAGED_RUNTIME.md).

## Historical 2.1.0 replacement packages

The 2.1.0 download and dependency corrections were delivered under a reused version
number; their SHA-256 manifests distinguished their binaries. Some earlier packages
were labelled 2.1.1. Those same-version replacement instructions are historical.
The current application is 2.3.0 and still requires a fresh application folder;
existing working Gateway runtime files should be retained.
