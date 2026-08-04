# Security policy

## Supported version

Security fixes are applied to the latest published IBC Manager release. Older
releases should be upgraded before reporting a defect that may already have been
corrected.

## Reporting a vulnerability

Do not open a public issue for a vulnerability that could expose credentials,
modify application-owned files, target another process, or permit unintended
remote control.

Use GitHub's private vulnerability-reporting feature for this repository. Include:

- the affected IBC Manager version;
- operating system and Java version;
- a minimal reproduction;
- the expected and observed behavior;
- whether credentials, IBC command access, process ownership, or filesystem
  integrity may be affected;
- logs with usernames, account identifiers, paths, and secrets removed.

Do not include live IBKR credentials, TOTP seeds, account numbers, session tokens,
or an unredacted diagnostic archive.

## Security boundaries

IBC Manager does not generate TOTP codes and does not modify official IBC. It can
store an IBKR password with Windows DPAPI and temporarily provide that password
to IBC during login. Compromise of the active Windows user can therefore expose
the session and its credentials.

The green **API listener detected** indication means only that the operating
system reports a local TCP listener on the configured API port. IBC Manager does
not connect to that port for status monitoring. The indication does not verify
the listener's process ownership, IB API handshake, account, permissions,
read-only state, market-data entitlement, or trading readiness.

Keep the IBC command server bound to loopback unless remote access is deliberately
secured at the host and network layers.

IBC Manager does not create an API or command-server client connection on every
status refresh. Command readiness is taken from IBC lifecycle output; API and
reattachment fallback readiness use passive operating-system listener-table
inspection. Only explicit IBC commands create command-server connections.
