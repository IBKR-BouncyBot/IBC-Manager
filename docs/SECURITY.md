# Security model

## Threat model

IBC Manager protects against accidental credential disclosure, casual offline
file inspection, cross-profile process termination, malformed configuration,
and common unattended-operation cleanup failures. It does not claim to protect
credentials after the active Windows user or machine is fully compromised.

## Credential controls

- Persistent profile files contain no password field.
- Manager-owned persistent `config.ini` files reject nonblank `IbPassword` and
  `FIXPassword` values in every active duplicate, regardless of key case. They
  also reject assignment-shaped password text hidden in comments or malformed
  raw lines. Imported manager-owned configurations blank active password values
  and conservatively redact non-setting occurrences before persistence.
- Windows encrypted mode uses DPAPI `CurrentUser` scope plus profile-specific
  entropy.
- The DPAPI helper script is passed as an encoded PowerShell program. Plaintext
  input is sent through stdin, not command-line arguments. Decrypted UTF-8 bytes
  return as strict Base64 rather than plaintext console output, avoiding
  code-page corruption and reducing accidental terminal exposure.
- The launch command contains paths and non-secret mode settings only.
- Config values, launch-script values, scheduled-task values, and profile
  identifiers reject CR, LF, NUL, NEL, and Unicode line/paragraph separators.
- API and command ports are probed before credential decryption; an occupied
  port fails launch before a password-bearing runtime config exists.
- Temporary runtime configs are ACL/permission hardened and remain available for
  the complete lifetime of the official `StartIBC.bat` wrapper, because that
  supervisor reuses the same path for automatic restart and timeout recovery. A
  detached relay owns the exact cleanup path and scrubs/deletes the file after
  the wrapper exits; failed launches and stale files are cleaned separately.
- Secrets and known IBC password syntaxes are redacted from app logs and
  diagnostic bundles. Redacted config rendering processes every duplicate and
  every comment/raw line rather than only the final active value.
- Java parent console handlers are disabled for the application logger so
  unredacted records cannot bypass the safe formatter.

## Buffered-log controls and trade-offs

Manager-owned application and profile-process logs are buffered in memory and
normally committed to disk once every 60 seconds. The application logger applies
redaction before records enter its buffer. Profile console bytes are forwarded
live to the GUI and buffered by a detached relay so the child process cannot
block when the GUI exits. Windows packages retain the bundled runtime's Java
process launcher specifically for this relay; package validation requires the
exact nonempty `runtime\bin\java.exe` before publication.

The relay descriptor is created in the owner-restricted profile runtime
directory, is itself permission-hardened, contains no manager-stored password,
and is deleted before the official IBC launch command begins. The relay keeps
consuming process output after the GUI exits and performs a final disk commit
when the child exits. Application shutdown similarly commits the final partial
application-log batch.

A failed scheduled write retains the pending data for a later retry or final
close attempt. The reduced write cadence has an explicit durability trade-off:
a hard process crash, operating-system failure, or power loss can lose up to
approximately 60 seconds of manager-owned buffered records. IBC, TWS, and IB
Gateway may independently write their own files at a different cadence.

## Prerequisite bootstrap controls

The Windows launch/build scripts can install missing tools, but only after an
interactive `[Y/N]` permission prompt. A decline returns a distinct nonzero exit
code before any managed-tool directory is created.

Managed Java is installed under the current user's application-data directory:

```text
%LOCALAPPDATA%\IBCManager\tools
```

The bootstrap does not uninstall existing Java versions and does not modify the
persistent machine/user `PATH` or `JAVA_HOME`. Java 8 may therefore remain
installed for legacy software while IBC Manager uses an explicit Java 17 path.

Supply-chain controls:

- Microsoft OpenJDK 17 is downloaded from Microsoft's stable major-version URL.
- Its archive is checked against Microsoft's separately published SHA-256 file
  before extraction.
- Extracted tools are executed for version/tool verification before activation.
- WiX is requested through Windows Package Manager using exact package ID
  `WiXToolset.WiXToolset`; package/source agreements are accepted only after the
  manager's own explicit consent prompt.
- No downloaded text is evaluated as PowerShell code.

Managed-tool replacement uses a named mutex, a staging directory, and backup
rename. A failed activation attempts to restore the previous managed tool. Once
a verified replacement is active, failure to remove the obsolete backup is
reported but does not discard the working replacement. Temporary archives and
staging directories are removed in `finally` blocks.

Source builds use a separate operating-system file lock keyed by a SHA-256
digest of the canonical source-tree path. The lock contains no credentials or
account data, lives outside build output removed by `clean`, and is held only
while a mutating build target is active. This prevents concurrent local builds
from deleting or replacing each other's classes and release archives.

The GUI's separate single-instance guard uses both an owner-hardened native file
lock and a JVM-local normalized-path reservation. The JVM reservation prevents a
same-process duplicate attempt from opening and closing a second descriptor for
the lock file, avoiding accidental release of process-wide native locking state
on platforms with POSIX `fcntl` semantics.

The batch/PowerShell bridge contains only verified executable paths. It contains
no IBC username, password, token, or account data and is deleted immediately
after import. Windows PowerShell is invoked with process-scoped execution-policy
bypass so downloaded ZIP zone metadata cannot prevent the local script from
running; this does not alter the system execution policy.

## Official batch-launcher path restrictions

IBC Manager delegates session startup to official `StartIBC.bat`. That script
removes outer quotes and re-expands user-selected paths through `cmd.exe`,
including inside command blocks and in some unquoted commands. The Manager
therefore rejects these characters in any path transported to the wrapper:

```text
" % ! & | < > ^ ( )
```

This is intentionally stricter than normal Windows path syntax. It prevents
command injection and parsing ambiguity but also excludes common locations such
as `C:\Program Files (x86)`. The UI names the offending character and recommends
batch-safe locations such as `C:\IBC`, `C:\Jts`, and `C:\IBKRSettings`. The
restriction must not be bypassed merely to accept a convenient path; broader
path support requires replacing or correcting the unsafe batch transport.

## Java Properties configuration boundary

IBC configuration bytes are interpreted by the JDK's full-file
`Properties.load(InputStream)` parser. Manager-owned writes use an authoritative
map from that same parser and a separate formatting scanner. If malformed syntax
causes the two to disagree, persistent mutation fails closed until the user
corrects or explicitly canonicalizes the file. This prevents a comment-preserving
scanner from silently assigning different credentials or settings than IBC.

Canonical output escapes Unicode, separators, and literal backslashes and is
loaded again by the JDK before it can reach IBC. Raw single-backslash diagnostics
never echo sensitive values.

## Official IBC installer controls

The GUI can download the tested official Windows IBC release after an explicit
confirmation. This operation is separate from the Java prerequisite bootstrap
and does not write credentials.

Controls include:

- the exact GitHub REST endpoint
  `https://api.github.com/repos/IbcAlpha/IBC/releases/latest`, with no generic
  website scraping or mutable browser redirect used for version selection;
- rejection of draft, prerelease, malformed, below-floor, duplicate-asset, and
  unexpected-URL metadata;
- selection of exactly `IBCWin-<resolved-version>.zip`;
- HTTPS on the standard port only;
- redirect hosts restricted to `github.com` and GitHub-controlled
  `*.githubusercontent.com` release hosts;
- bounded metadata, connection/read, transfer, and archive sizes;
- a transfer-computed SHA-256 plus enforcement of the size and SHA-256 digest
  published in GitHub's release-asset metadata;
- unique staging beside the destination and cleanup after failure/cancellation;
- ZIP traversal, absolute-path, NUL, control-character, Windows reserved-name,
  trailing-dot/space, duplicate-path, entry-count, per-entry, and total-expansion
  protections;
- validation that the external version and embedded JAR version match, are at or
  above the compatibility floor, and expose the required distribution files,
  `scripts\StartIBC.bat` switches, helper scripts, and classes inside `IBC.jar`;
- extraction of the selected IBC JAR's Java class-file requirement, followed by
  launch-time rejection when `StartIBC.bat` would use an older Java runtime;
- rejection of symbolic-link, non-empty invalid, differently versioned, and
  concurrently changed destinations;
- no merge, overwrite, or automatic removal of an existing `C:\IBC`;
- no modification of the downloaded IBC files or JAR.

The dynamically obtained SHA-256 protects against transfer corruption and an
unexpected asset at the selected URL, but it is metadata from the same GitHub
release account rather than an independent maintainer signature or a checksum
reviewed and embedded in IBC Manager. Consequently, the dynamic channel trusts
the current official IbcAlpha/IBC GitHub release. A user
requiring stronger provenance should still verify IBC independently before
selecting the folder.

Creating `C:\IBC` can require administrator permission. The installer does not
silently elevate itself; an access-denied error instructs the user to rerun the
manager as administrator. This makes the privilege transition visible.

## Residual credential exposure

IBC's normal username/password login requires plaintext to exist briefly:

- in the manager process memory;
- in the PowerShell/DPAPI pipe during decrypt;
- in the temporary runtime config;
- in IBC's own process memory after it reads the config.

Java strings and third-party process memory cannot be reliably zeroized. The
application minimizes lifetime and file persistence but does not claim perfect
memory erasure.

## Residual bootstrap risks

- A compromised active Windows user can replace local scripts or intercept
  processes regardless of checksum verification.
- HTTPS and published checksums reduce download corruption/substitution risk but
  do not replace code signing or a trusted operating system.
- WiX installation is performed by WinGet and may require administrator consent.
- Corporate proxies, TLS interception, disabled WinGet, or offline operation can
  prevent automatic installation. Failure is explicit; no unverified archive is
  activated.
- The generated environment file uses the active Windows console code page. An
  unrepresentable path fails explicitly instead of being written with replacement
  characters; the error instructs the user to switch the Command Prompt to UTF-8
  with `chcp 65001` or use an ASCII-only tool path. The Windows checklist still
  requires paths with spaces and non-ASCII characters to be validated.

## Network controls

The default IBC command-server bind address is `127.0.0.1`. Non-loopback or
blank bind addresses produce validation warnings. Remote `ControlFrom` entries
also produce a warning.

Normal status monitoring does not create a network connection to either the IB
API port or the IBC command port. API and reattachment fallback readiness are
read from the local operating-system TCP listener table, while steady-state IBC
command readiness is derived from IBC lifecycle output. Launch preflight forces
a fresh passive listener snapshot before credentials are loaded and fails closed
when the socket table is unavailable. Explicit user-requested IBC commands still
create one local command connection.

After an explicit GUI confirmation, the optional IBC installer contacts GitHub's official latest-release API, selected release asset, and GitHub-controlled redirect hosts. The prerequisite
bootstrap contacts official Microsoft endpoints and WinGet only after its own
explicit consent. The manager does not contact IBKR directly.

## Bounded control-file and transaction controls

Application-owned profiles, configuration, credential blobs, runtime files,
process identities, relay descriptors, installer control data, diagnostics, and
deletion metadata have explicit byte limits and are opened without following
symbolic links. Strict UTF-8/ASCII decoding is used where text is expected.
Atomic replacement avoids partially written active state.

Profile save restores the previous profile, managed configuration, and stored
credential after a late failure. Profile deletion stages profile/runtime state in
a private PREPARED/COMMITTED transaction directory, restores interrupted
PREPARED work, completes durable COMMITTED work, and verifies that transaction
metadata matches the profile UUID encoded in the transaction directory name.

In-memory application, process, and GUI log queues are bounded. When a queue
limit is reached, old data is discarded with an explicit marker rather than
allowing unbounded memory growth. Routine disk writes remain batched every 60
seconds, so a hard crash can still lose the current interval.

## Process controls

- Exact PID and process start instant identify a managed process.
- Manual Start, Stop, Restart, and Pause require a profile-specific modal
  confirmation; Cancel is the default and does not dispatch a runtime operation.
- Start, Stop, Restart, and Pause share the same emphasized minimum button size,
  bold typography, accessibility metadata, and separate visual accents.
- Starting a live profile includes an explicit live-trading warning. Stop,
  Restart, and Pause disclose the expected connectivity impact.
- Graceful stop is attempted through IBC first.
- Forced cleanup targets the selected process tree only.
- A manager exit is blocked when a password-bearing runtime config remains in
  use or cannot be safely removed.

## File controls

Writes use same-directory temporary files and atomic replacement where the file
system supports it. Backups are created for persistent profile/config changes.
On POSIX systems, private directories use mode `0700` and private files `0600`.
On Windows, the implementation applies owner-restricted ACLs where supported,
including directory traverse permission required by the owning user.

## Operational recommendations

- Use a dedicated, non-administrator Windows account.
- Enable BitLocker.
- Keep the IBC command server loopback-only.
- Use separate settings directories and ports for every instance.
- Begin with paper trading and validate the expected IBKR account in the API
  client, not only in this GUI.
- Restrict remote desktop access to a trusted local network or VPN.
- Treat a diagnostic ZIP as sensitive even though automatic redaction is used.
- Keep the source/release ZIP and its SHA-256 manifest for reproducible recovery.
