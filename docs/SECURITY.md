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
