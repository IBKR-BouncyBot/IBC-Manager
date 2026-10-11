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

# Engine integration in 2.0.4

The included engine revision is 3.24.2-manager.4. Java patch 0004 removes the eager
Toolkit lookup from generic command dispatch; reconnect requests still require a
usable desktop. Patches 0001/0002/0003 retain their earlier authentication changes.
The four Java patches reproduce the maintained Java tree from the archived reference.
The separate resource-patch series changes all four maintained Windows Java launch
branches to preserve stderr. The upstream snapshot and original archived scripts
remain unchanged; the maintained StartIBC.bat is intentionally different.

Configuration now comes only from format-6 Profile data plus protected credentials.
Legacy external/managed INI readers are used only for one-time import. Generated
config.ini is diagnostic output; the runtime copy is built from the same profile.
The process and engine boundaries below retain the original integration design;
profile format 6 and the authentication behavior are unchanged from 2.0.3.

---

# Retained integration design

## Boundaries

The Windows/IB Gateway product includes a source-built IBC-derived engine.
It has no external IBC installation mode, downloader, directory selector or
runtime fallback to a legacy path. `legacyIbcPath` survives only as inert upgrade
metadata; it is not used in a launch command. Base/existing config.ini input is
restricted to one-time migration; current profiles own their complete settings.

The supervisor is outside the Gateway JVM. The engine remains alongside Gateway
so the existing Swing-component handlers can be reused. The first integration
retained the StartIBC Windows supervisor and its official Java-options helper. The
2.0.4 supervisor script differs only in stderr routing, as documented above.
This avoids independently reimplementing token restart, cold restart, pause,
exit-code handling, VM options and Java-runtime discovery in one release.

The IBC command port remains in use for control commands. The original integration added
structured lifecycle reporting; it does not claim to replace the command server
with a new authenticated RPC protocol or eliminate runtime credential files.

## Source and build

- `engine/upstream/src`: supplied upstream Java source snapshot, byte-preserved.
- `engine/upstream/windows`: original supplied Windows launcher/helper.
- `engine/src/main/java`: maintained source, Java 17 target; no IBKR dependency.
- `engine/src/test/java`: real engine contract and Swing/command tests.
- `engine/resources`: Windows scripts, version, config and license payload.
- `engine/PROVENANCE.json`: supplied archives' SHA-256 and upstream revision.
- `engine/UPSTREAM_SHA256.json`: byte hashes of the archival reference snapshot.
- `engine/patches/series`: ordered integration, authentication and command-dispatch patches.
- `engine/resource-patches/series`: independently reproducible Windows script changes.

The application build first compiles the engine, builds a deterministic IBC.jar,
and packages it with the exact resource set. It generates a per-file SHA-256
manifest and embeds both the payload ZIP and manifest inside the Manager JAR.
The engine classes are not placed on the GUI process's classpath.

Deployment uses an owner-restricted cache under the Gateway settings directory:
`.ibc-manager-engine/3.24.2-manager.4-<content-id>/`.
The exact payload is checked on each use. Staging and a file lock serialize
extraction; altered, symbolic, missing or extra payload files are rejected.
This verifies equality to the bundled payload, not authenticity of an independently
replaced Manager JAR. Release provenance still requires a trusted distribution.

## Engine changes

The Gateway entry checks Windows and rejects FIX; the TWS entry rejects launch.
The shared IbcTws class name is retained internally because it holds the upstream
common bootstrap and handler registrations. Retaining shared/TWS-named classes
is not user-visible TWS support. Gateway invocation uses reflection and unwraps
the original cause, so neither proprietary JARs nor fake IBKR classes are needed
at compile time. IbcVersionInfo is generated with the supplied tag's version.

Login state changes, Gateway start, main-window availability and command-server
lifecycle emit bounded messages through IBC's preserved original output stream:

```text
IBC_MANAGER_EVENT|1|<engine-JVM UUID>|<monotonic sequence>|<fixed event>
```

No username, password, account, free text or token is carried in an event. The
Manager rejects malformed, out-of-order, duplicate and retired-generation
messages. Wrapper launch markers revoke old readiness. A reconnect command
requires both completed login and observed main-window readiness. The engine
itself rejects reconnect commands when a main window is absent, rather than
throwing a null-source GUI exception.

The normal Windows wrapper logs and command replies continue to be interpreted
for supervisor decisions. There is no claim that arbitrary logs are a privileged
network protocol: this channel is inherited from the owned process, not exposed
on an additional public port. Kernel/user-account compromise is outside this
boundary.

## Preserved behavior and limits

The original Gateway handler registration/order, credential configuration
contract and Windows wrapper restart semantics are retained. The 2FA relogin
scheduler is intentionally changed in engine revision manager.2: an independent
challenge deadline replaces the old dialog-close threshold and exit tasks. The
existing login handler still performs the actual credential submission. Logical changes are localized and testable; line endings are normalized
in maintained Java files while the reference source remains byte-exact.

This is a source-level preservation strategy, not a proof of all behavior against
closed-source Gateway. Real Windows Gateway 10.45/10.50 startup, scheduled token
restart, fresh recovery, pause/resume, 2FA retry and actual API connectivity must
still pass the paper-account acceptance matrix. The integration does not itself
repair every possible hang in IB Gateway.

## 2.0.1 retry events

`SECOND_FACTOR_RETRY_ARMED`, `SECOND_FACTOR_RETRY_DUE`,
`SECOND_FACTOR_RETRY_STARTED` and `SECOND_FACTOR_RETRY_BLOCKED` extend the
fixed event vocabulary of protocol 1. They contain no secrets or arbitrary text.
They do not imply server acceptance, push delivery or login completion. Manager
keeps the latest real login state authoritative and ignores retired generations.

## 2.0.4 targeted changes

See V2_0_4_RELIABILITY.md and the engine maintenance guide for restart-wide monitoring,
headless command dispatch and redacted stderr capture. Java patch 0004 and resource
patch 0001 are independently reproducible. The three earlier integration/authentication patches are unchanged.
