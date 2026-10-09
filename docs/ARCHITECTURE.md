# IBC Manager 2.0.2 architecture

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
