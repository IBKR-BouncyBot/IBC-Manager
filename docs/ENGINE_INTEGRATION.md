# Engine integration in 2.0.2

The included engine revision is 3.24.2-manager.3. Patch 0003 repairs returned login
forms, reconstructs frame registration safely and separates credential preparation
from submission. The upstream snapshot and original Windows scripts remain unchanged.
Patch series 0001/0002/0003 reproduces the maintained Java tree.

Configuration now comes only from format-6 Profile data plus protected credentials.
Legacy external/managed INI readers are used only for one-time import. Generated
config.ini is diagnostic output; the runtime copy is built from the same profile.
The following 2.0.0 description records the original process/engine boundaries;
its external/base config-input statement is superseded by this release's migration.

---

# Engine integration in 2.0.1

## Boundaries

The Windows/IB Gateway product includes a source-built IBC-derived engine.
It has no external IBC installation mode, downloader, directory selector or
runtime fallback to a legacy path. `legacyIbcPath` survives only as inert upgrade
metadata; it is not used in a launch command. Base/existing config.ini input is
restricted to one-time migration; current profiles own their complete settings.

The supervisor is outside the Gateway JVM. The engine remains alongside Gateway
so the existing Swing-component handlers can be reused. The first integration
retains the StartIBC Windows supervisor and its official Java-options helper.
This avoids independently reimplementing token restart, cold restart, pause,
exit-code handling, VM options and Java-runtime discovery in one release.

The IBC command port remains in use for control commands. This release adds
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
- `engine/patches/series`: ordered, reviewable logical changes; integration first, then the independent second-factor deadline.

The application build first compiles the engine, builds a deterministic IBC.jar,
and packages it with the exact resource set. It generates a per-file SHA-256
manifest and embeds both the payload ZIP and manifest inside the Manager JAR.
The engine classes are not placed on the GUI process's classpath.

Deployment uses an owner-restricted cache under the Gateway settings directory:
`.ibc-manager-engine/3.24.2-manager.3-<content-id>/`.
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
