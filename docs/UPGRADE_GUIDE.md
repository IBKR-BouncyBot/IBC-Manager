## Same-version JavaFX dependency repair

An older managed payload may omit jdk.jsobject.jar. Stop all profiles using its
installation, disable and Save on each owning profile, then enable and Save with
this corrected package. Do not copy a JAR into the owned payload or edit its hash
receipt. Verify payload/javafx/jdk.jsobject.jar exists after successful setup and
Gateway starts without FindException. Missing dependencies must be ERROR, not a
normal scheduled shutdown. See [dependency details](JAVAFX_DEPENDENCY_FIX.md).

# Upgrade to IBC Manager 2.1.0

Stop all profiles, exit the old Manager, and back up its complete data directory.
Extract the new application separately. Retain the same Windows account, computer
and data directory so existing DPAPI credentials remain accessible.

The Windows native package contains Microsoft Java 25. The Java-release run.bat
asks permission to install an isolated Microsoft OpenJDK 25 when required. It does
not delete existing Java 17 installations or convert your manual Gateway JBR/JavaFX
setup into managed ownership automatically.

## Replacing an earlier 2.1.0 or 2.1.1 package

This 2.1.0 distribution contains both download corrections. Its SHA-256 manifest
identifies the new binaries despite the reused requested version number. Extract
it to a fresh folder and use its run.bat, or rebuild its Windows portable package.
Do not merge source/build folders or reuse a previously compiled JAR. Close all
old Manager processes first; data format 7 and engine manager.5 are unchanged.
The managed-runtime state is compatible in both directions across these packages.
Do not delete manual JBR/JavaFX, Gateway files or .vmoptions merely to retry setup.
A native installer may reject a version downgrade from 2.1.1; the fresh portable
folder avoids that installer-version conflict. Back up data before replacement.

## Profiles and TWS

Profile formats 1-6 remain readable. Saving now writes format 7, including the
managed-runtime flag. Current format-6 profiles are not rewritten merely because
they were loaded. Older versions must not write the new format-7 profiles.

Previously disabled legacy TWS profiles remain disabled. Review their application,
settings directory, ports, credentials and schedules before enabling them. The
restored support does not start old TWS sessions without your decision.

Copy creates a new configuration identity with unused ports, auto-start off,
disabled status and manual credentials. No settings files or runtime state are
copied. Select a separate settings directory for concurrent instances.

## Runtime and engine

The workaround checkbox is opt-in and changes the selected offline installation's
.vmoptions and the selected profile override, not Manager's own runtime. Existing
manual Java/JavaFX folders remain yours. On disable, the previous override and
module options return; those may themselves describe your manual JBR setup.

Engine 3.24.2-manager.6 preserves the 2.0.4 watchdog, command-dispatch and stderr
fixes, restores TWS bootstrap/readiness, and adds full-line VM-options reading.
Its versioned cache is separate from an active older engine cache.

## Rollback

While using 2.1.0 and with sessions stopped, disable managed setup for every owner
and save. Then close Manager and restore the pre-upgrade data directory together
with the previous application version. Do not point 2.0.4 at format-7 profiles.

If setup reports an interrupted commit or removal, retain its journals and files.
Reopening Manager can finish or undo a recognized transaction. Unexpected profile,
VM-option or file changes require review; deleting the journal loses that evidence.

## Pinned-runtime compatibility update within 2.1.0

Use a fresh application folder because the release number is unchanged. Engine cache
revision changes to 3.24.2-manager.6, but profile format remains 7. Back up Manager data
and stop sessions before updating. Existing non-pinned managed runtimes are deliberately
not modified on startup: disable and Save the workaround on all owners of that
installation, then enable and Save to install JBR25.0.4-b508.27. Never substitute files
inside the owned payload or edit its hashes. The removal path supports earlier recorded
builds; manual runtime/settings/credentials are retained.

See PINNED_RUNTIME_DIAGNOSTICS.md for exact changes and target-machine acceptance.
