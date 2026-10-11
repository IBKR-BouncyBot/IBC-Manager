# Security boundaries

The product is an interactive Windows application, not a Windows service.
Use a dedicated non-administrator account where practical and restrict remote
access appropriately. Do not publish diagnostic bundles without checking for
remaining personal/account information.

DPAPI credential blobs remain associated with the original profile UUID and
Windows context. While Gateway is managed with saved credentials, a plaintext
runtime configuration is retained for the complete lifetime of the official `StartIBC.bat` wrapper
so replacement children can log in. The file and directory are owner-restricted;
its detached relay owns final cleanup after wrapper exit. Normal login/2FA
progress does not remove a file needed for subsequent restarts. A hard crash or
power loss can leave residual files; the existing cleanup/recovery rules apply.
Integration does not add guaranteed memory zeroization or protection from malware
running as the same Windows user.

The only engine source is the payload embedded in the Manager JAR. Exact-file
hash verification, no-follow checks, extraction staging and file locks protect
against accidental or detectable cache substitution. The manifest is not a code
signature: an attacker able to replace the entire trusted Manager JAR can replace
its manifest too. Distribute the published checksums over a trusted channel and
provide matching source. The caches contain engine code, not login secrets.

Engine protocol messages use fixed event names, a per-JVM UUID and monotonic
sequence; no credentials/account/dialog text is included. They travel through
the inherited process output, not another public listener. The existing command
port controls session operations and should stay loopback-only. It is not an
IBKR API port and is not a general authenticated remote-management API.

Normal status monitoring does not open raw API connections. API listener
ownership does not establish a handshake, account, permission or upstream-server
connection. The trading client must verify those itself.

Automatic cleanup targets only the owned wrapper and verified descendants.
Manual Stop never silently becomes force termination. A stalled-start recovery
has separate bounded permissions and persistent loop limits. 2FA warm retry is
not approval bypass. The Manager must remain active for its recovery watchdog.

Migration backups are protected but may contain sensitive historical settings.
Keep complete stopped backups until acceptance passes. Never concurrently run old
and new Manager versions against the same data directory.

In 2.0.4 the relay redacts complete logical stdout/stderr lines before live/disk
output. Lines exceeding 64 Ki characters are omitted in full, not partially exposed.
This is best-effort pattern redaction, not a guarantee that all arbitrary personal
information or secrets are recognized. Windows/Gateway native logs outside this
relay keep their own logging and protection rules.

## 2.1.0 runtime downloads

Runtime installation is explicit and checks publisher hashes over TLS before executing
the downloaded Java module probe. Only approved HTTPS locations are followed. Owned
payload integrity is checked before launch/removal. Setup changes a shared installation
file, so all sessions using it must be stopped; manually launched or separately managed
processes must also be stopped by the operator. Private journals are not public logs.

Removal preserves manual runtime folders and conflicting changes. It tracks both the
profile UUID and data directory and keeps a hash receipt during partial deletion.
Do not relocate an enabled owned installation or edit its journals to bypass checks.
Publisher checksums establish transfer integrity, not IBKR certification or a guarantee
against a compromised publisher. Native Windows/Java 25 acceptance is separate from
Linux fixture tests. No Windows lock, RDP, VDD or authentication bypass is performed.

## JavaFX repository checksum compatibility

Maven SHA-256 is optional. Setup permits SHA-1 only on a direct, typed HTTP 404 for
the exact selected JavaFX SHA-256 sidecar, fetched from the approved HTTPS publisher.
No response-body/message parsing converts an error into permission to downgrade.
Invalid/mismatched SHA-256, other status codes, transport failures and missing JARs
fail closed. SHA-1 is not collision-resistant and is not a publisher signature;
transport authenticity relies on TLS and the repository. No MD5 fallback exists.
The local SHA-256 audit manifest continues to protect later reuse from byte changes.

## Managed-runtime publisher checksum availability (2.1.0)

The explicitly consented workaround may now install JBR or JavaFX artifacts whose
publisher checksum cannot be downloaded. Available SHA-512/SHA-256/SHA-1 checksums
are still checked; disagreement or malformed successful responses are not bypassed.
Unavailable checksum downloads are disclosed in progress, the log and provenance.
Only approved HTTPS resources are used. TLS identity failures, unsafe redirects,
required artifacts and compatibility checks remain blocking. Local SHA-256 hashes
protect subsequent integrity checks but do not establish publisher authenticity.
This exception does not apply to Manager's Microsoft-Java prerequisite bootstrap,
embedded-engine verification, build/test gates, or already-installed payload hashes.
