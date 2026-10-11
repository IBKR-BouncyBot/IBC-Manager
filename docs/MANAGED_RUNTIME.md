> Current 2.1.0 dependency correction: install all eight OpenJFX modules, including
> `jdk.jsobject`. A returned fatal module-resolution error is not an unavailable
> check. Old seven-library installations need disable/Save then enable/Save.
> See [JAVAFX_DEPENDENCY_FIX.md](JAVAFX_DEPENDENCY_FIX.md).

# Managed JBR / JavaFX workaround (2.1.0)

## Scope and evidence

The reported Gateway 1050/Java 25 failure was a HeadlessException during window
construction after disconnected-RDP restarts. The user confirmed a manual JBR 25 +
JavaFX setup subsequently completed a warm restart while RDP was disconnected and
Windows remained locked, without NoMachine or tscon. This release automates that
configuration, not Java's entire graphics implementation or all future build behavior.
No automatic workstation unlocking, console transfers or virtual-display installation
is performed. Production remains Windows only. The download option is Windows x64.

## Two independent Java choices

Manager's scripts use Microsoft OpenJDK 25, installed in its existing isolated tools
mechanism. The optional workaround selects a separate JetBrains Java 25 for the
TWS/Gateway process. Updating Manager's runtime alone is not the Gateway workaround.
The maintained engine remains Java-17-compatible for older installations; overrides
for application builds 1048 and newer are required by Manager to be Java 25+.

## Optional runtime verification

After downloading and checking the required files, Manager tries a 30-second local
JavaFX module-inventory command. If the command cannot be run/captured, times out,
fails without a recognizable module diagnostic, or provides no recognizable inventory,
setup continues with a warning. No output is treated as unavailable, not as proof of a
compatible runtime. A completed failed probe reporting a fatal JVM boot-layer or module
resolution error remains blocking, as does an inventory proving missing/wrong-version modules.

The outcome is written to `payload/download-provenance.txt` and unavailable outcomes
are logged with bounded, redacted stdout/stderr. This addresses the previous empty
"Downloaded Java/JavaFX runtime verification failed:" popup. It does not prove the
runtime will launch Gateway or bypass later genuine application/Java startup failures.
The separate Start-time Java version check is unchanged. See [details](OPTIONAL_RUNTIME_CHECK.md).

## Download compatibility

JetBrains' approved cache-redirector can redirect to the exact distribution
`d2xrhe97vsfxuc.cloudfront.net`, dropping the `/intellij-jbr/` prefix. The allowlist
exception remains bound to the initial JetBrains publisher and exact JBR25 Windows
x64 ZIP/checksum identity. It is not a wildcard CDN permission.

Publisher checksum downloads are optional when unavailable, at the user's request.
JavaFX first tries SHA-256, then SHA-1 if the first resource cannot be downloaded.
JBR tries SHA-512. If no candidate checksum is downloadable, installation proceeds
with an explicit per-artifact warning; the downloaded runtime still has to pass all
archive, Windows-x64, version, native-library and JavaFX-module checks.

Only typed HTTP 4xx/5xx or transport failures at the requested checksum resource (or
its equivalent approved JBR CDN path) permit continuation. An empty/oversized HTTP
200 body, malformed checksum, payload/hash mismatch, unsafe or changed redirect,
TLS validation failure, cancellation or incomplete required artifact is not bypassed.
Maven and JBR errors on another resource are not interpreted as absent checksums.

When a checksum is available it is compared normally; a mismatch never triggers
an unchecked retry. Each skipped comparison is recorded as SKIPPED, not VERIFIED,
in `payload/download-provenance.txt`, progress and the Manager log. The provenance
includes failed sidecar URLs/status evidence and a local SHA-256 of every downloaded
artifact. HTTPS provides transport protection but the locally calculated digest is
not independent evidence of publisher authenticity. These weaker-integrity downloads
are specifically disclosed in the consent prompt before Save.

Retry the explicit setup from Edit Profile after replacing the complete application.
Previously installed, locally hash-checked managed runtimes are reused only when the recorded JBR matches the pin. A different build needs explicit removal/reinstallation, not in-place edits.

## Explicit consent and download policy

The checkbox immediately explains downloads, locations, shared-file effects, removal,
limitations and permissions. Nothing changes until Save. On Save, every known session
using the installation must be stopped, including a tracked detached session. Stop
manually launched TWS/Gateway instances and those managed under other Windows accounts
as well; Manager cannot prove every external program is inactive.

Setup uses exactly **JBR 25.0.4-b508.27**, Windows x64 vanilla:
`jbr-25.0.4-windows-x64-b508.27.zip`. It does not scan GitHub's release list or
substitute another JBR if that archive is unavailable. The publisher's SHA-512
sidecar remains optional when unavailable, with the existing recorded warning.
The fixed URL, local release version and any available JBR build metadata are checked.
JavaFX resolves the newest
numeric stable 25 release from OpenJFX Maven Central metadata. Eight Windows modular
JARs (javafx.base, javafx.graphics, javafx.controls, javafx.fxml, javafx.media,
javafx.swing, javafx.web and jdk.jsobject) are checked against their
published SHA-256 when downloadable, then published SHA-1, or the explicit
unavailable-checksum behavior described above.

These JARs include Windows natives and are a supported OpenJFX packaging method; a
separate Gluon SDK ZIP is not needed. The downloaded JVM is checked as a Windows x64
Java 25 JetBrains runtime and asked to resolve all eight OpenJFX 25 modules before
profile/VM-options commit. This probe does not launch IBKR or authenticate an account.
Publisher URLs and computed checksums are recorded in payload/download-provenance.txt.

TLS, restrictive publisher/redirect paths, bounded response/download/ZIP sizes and
Windows-ambiguous-path checks apply. Unavailable checksum downloads do not stop setup;
errors in required downloads, invalid content, available checksum comparison, archive
validation, a fatal module-resolution error or a successful inventory proving incompatible
modules leave existing options/profile unchanged.
An unavailable executable probe is instead logged and skipped. Publisher
signatures are not independently verified. The local ownership hashes protect later
integrity/cleanup even when a publisher checksum was unavailable during installation.

First-time setup can transfer hundreds of MB and needs write permission to the selected
installation. Protected installations may require elevated rights; the program does not
silently bypass access controls. Progress appears in the GUI. Cancel is cooperative
while downloading/verifying and before the short commit phase; it does not interrupt
an atomic save halfway through. HTTP connect/read timeouts bound stalled cancellation.

No packages are downloaded at ordinary startup. A locally hash-checked already-managed installation matching the pin
is reused. New releases are not silently installed into a working runtime. To select a
newer release, release all registered profiles then enable again during maintenance.

## Layout and options

The helper uses the same selected offline layout as StartIBC, including alternate
TWS/Gateway layouts. Typical locations:

```
C:\Jts\ibgateway\1050\.ibc-manager-jbr25-ibgateway\
C:\Jts\1050\.ibc-manager-jbr25-tws\
```

Inside: locally hash-tracked payload/jbr, payload/javafx, native-cache, original/installed VM-options,
checksums, an ownership record and only temporarily a recovery/deletion journal.
The profile override becomes payload/jbr/bin. No system Java folder is replaced.

Only the selected installation's .vmoptions is changed. Existing module-path,
add-modules and JavaFX-cache lines are saved, then replaced with a marked block for
managed JavaFX. Other options remain. Existing custom module arrangements must be
reviewed: they are restored on removal, not merged with potentially conflicting FX
versions while enabled. Paths with spaces are quoted; the maintained StartIBC reader
now keeps complete option lines. An explicit java.awt.headless=true is rejected.

JavaFX can extract native libraries from these modular JARs. Its javafx.cachedir is set
to the owned native-cache folder. Known DLLs are hash-checked against the installed JARs;
they are removed along with the last owner's runtime. Unknown/changed cache files are
not blindly deleted. The original manually created C:\Java folders are never owned.

## Shared installations and removal

Ownership is keyed by both data-directory identity and profile UUID. Multiple registered
profiles can share one installed runtime. Disabling a profile restores its old override;
shared options/downloads remain until the last owner releases them. A manual profile
still referencing the owned runtime prevents last-owner deletion. Copy profile does
not copy ownership or an owned Java path.

Deselect, confirm, Save. Last-owner removal restores the original module settings,
preserves unrelated VM edits made later, and deletes the owned payload/cache/backup
record. If the marked block, payload, cache or rollback metadata has unexpected changes,
removal fails visibly instead of guessing or deleting foreign bytes. Files solely from
manual setup are never removed. A small per-installation lock file can remain; deleting
active synchronization files could break locking between Manager processes.

A durable journal links the VM-options change to the exact previous and next profiles.
Startup recovery finishes a committed change or restores the previous state. An
unrelated intervening profile/VM edit blocks recovery. A hash receipt allows interrupted
owned deletion to resume without treating missing already-deleted files as corruption;
changed leftover files are preserved. Do not manually delete the journals to hide an error.

Changing the application/root/version while managed ownership is active is refused.
Disable and save first. Profile deletion is refused until it releases managed ownership.
For rollback to 2.0.4, disable managed setup in 2.1 first, close it, then restore the
pre-upgrade data backup and old app together.

## Windows acceptance

The automated installer and publisher-response handling were tested with isolated fixtures
on Linux, not a Windows download/launch. The user-confirmed result was the earlier
manual setup. Run the complete Windows checklist: Microsoft 25 bootstrap, successful
fresh download, profile save, JavaFX module/native availability, .vmoptions quoting,
RDP-disconnected locked warm restart, cold login/2FA, cancellation, two-owner removal,
manual options preservation and final cleanup. Native file locks/antivirus can differ
from simulated interruption cases. Verify success predates RDP reconnection.

## Primary publisher documentation

- JetBrains runtime/releases: https://github.com/JetBrains/JetBrainsRuntime/releases
- OpenJFX Maven packaging: https://openjfx.io/openjfx-docs/maven
- OpenJFX native extraction/cache: https://github.com/openjdk/jfx/blob/master/modules/javafx.graphics/src/main/java/com/sun/glass/utils/NativeLibLoader.java
- Microsoft OpenJDK: https://learn.microsoft.com/en-us/java/openjdk/download
- JDK 25 Windows packaging: https://docs.oracle.com/en/java/javase/25/jpackage/packaging-overview.html

## Compatibility pin and current diagnostics

The pin returns to the exact JBR build used in the successful manual test. It is not
proof that all other variables in that test match the current automated installation,
nor that IBKR will accept every future Gateway build. No arbitrary identity values
are injected to suppress a server rejection. The full OpenJFX dependency set, optional
unavailable checksum/probe policy and safe shared ownership/removal are retained.

See [pinned-runtime repair and expanded diagnostics](PINNED_RUNTIME_DIAGNOSTICS.md).
