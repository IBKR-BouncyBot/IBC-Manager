# 2.1.0 architecture update

Manager's Windows runtime is Microsoft Java 25. TWS and Gateway both use the included
engine 3.24.2-manager.6; TWS startup and readiness are restored through upstream
handlers with a reflection-only entrypoint. FIX remains excluded. Format 7 adds
managed-runtime ownership while preserving existing format-6 data and credentials.

The new consent-based runtime service resolves and verifies JBR 25 and OpenJFX 25,
stages installation, shares ownership, journals VM-option/profile commits and
supports safe removal. It is separate from engine caching and normal startup.
See [managed runtime](MANAGED_RUNTIME.md) and [engine maintenance](ENGINE_MAINTENANCE.md).
The maintained launcher now preserves complete VM-option lines as well as stderr.
Existing startup/recovery/2FA/security boundaries remain.

## Historical 2.0.4 design below

The material below describes the preceding release. Its Gateway-only and format-6
scope statements are historical; the 2.1.0 changes above supersede them.

# IBC Manager 2.0.4 architecture

Windows-only Swing Manager supervises isolated IB Gateway/engine processes through
the retained Windows wrapper and detached logging relay. The maintained engine is
built from source, embedded as a verified payload and deployed to a content-addressed,
owner-restricted cache. An external IBC installation is never a launch option.

Configuration authority: format-6 Profile + DPAPI credential association. The Profile
editor is the sole UI; generated INI is an output, not another authority. Protected
runtime configuration remains alive for the wrapper lifetime and is cleaned after exit.
One-time legacy import is journalled and backed up. See SINGLE_PROFILE_CONFIGURATION.md.

Authentication retry runs in the engine: a monotonic deadline bound to a recognized
challenge schedules EDT operations. Preparation restores credentials before readiness
waits; submission uses the prepared handler without duplicate field writes. Structured
login events do not prove receipt of a phone notification or a successful API handshake.
See SECOND_FACTOR_RETRY.md.

Process-level startup recovery remains in Manager and is separate from authentication.
It rechecks generation/progress/listener ownership, respects Stop and pending recovery,
uses exact-tree termination only under its bounded policy, and has persisted rate limits.
Configuration commits acquire the controller monitor, so a recovery-cooldown gap is not
an opportunity to change the profile used by its upcoming launch.

File operations retain bounded reads, strict parsing, no-follow checks, owner permissions,
atomic writes and rollback. Manager-owned logs retain normal 60-second disk batching.
The command-server protocol and static Swing handlers remain an explicit integration
boundary. No general IPC rewrite, credential-file elimination or trading logic is claimed.

The no-progress startup episode survives failed child replacements. A real login/2FA
milestone or owned API listener ends it; process creation alone does not. Between-child
states do not authorize a kill. A failed target that changes during queued cleanup
retains pending recovery instead of recursively scheduling another termination.

The actual GUI export includes the bounded in-memory log snapshot. The maintained
Windows launcher merges child stderr with stdout, and the detached relay redacts
bounded complete lines before both live and buffered-disk delivery. Existing file
ownership, 60-second disk cadence and final flush semantics remain.
