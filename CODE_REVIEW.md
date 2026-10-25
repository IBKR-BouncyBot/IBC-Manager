# IBC Manager 2.3.0 — established-session expiry and exact runtime pins

Reviewed against the supplied 2.2.0 source. The 25 October diagnostic identifies
an established Gateway session still marked LOGGED_IN when the explicit expiry
dialog titled `IBKR Gateway` appeared. Both the old title/state gates and Manager's
startup-only event gate missed that sequence. A normal daily warm restart had
succeeded beforehand. The encrypted current application log does not establish
the proprietary upstream cause; this fix responds to the explicit visible error.

## Implementation boundary

The embedded engine owns exact dialog recognition, login-state eligibility,
shared Stop/Pause/Restart arbitration, dialog dismissal and bounded shutdown.
Its bundled Windows launcher owns fresh replacement startup and a per-session
pending guard. Manager only interprets the new fixed events for recovery/error
status; the process controller is unchanged. Separately, Manager's workaround
installer now selects and validates both requested runtime pins.

## Review findings addressed

- Preserve the real LOGGED_IN state and run graceful File / Close on a worker.
  The EDT only inspects/clicks Swing controls. A continuation handles OK opening
  another modal; an independent 30-second deadline prevents an indefinite wait.
- The deadline can halt only its own JVM, with the prepared regular marker still
  present. Marker loss cancels pending actions, releases ownership and reports
  failure. Already executed GUI actions cannot be rolled back.
- Stop/Pause/Restart claims are coordinated before expiry recovery commits.
  Automatic Re-login is suppressed during the committed shutdown. A later
  manual Stop retains its existing in-progress response; Force Stop is separate.
- The dedicated TOKENEXPIRED marker takes priority over saved warm data. The
  wrapper resets inherited ERRORLEVEL before creating the pending guard, and
  refuses failed/conflicting markers. It never broadly removes authentication data.
- TOKENEXPIREDPENDING prevents a failed replacement from reverting to a stale
  descriptor. Only actual successful engine login removes the matching guard.
  Existing explicit 2FA/display-timeout retry policies remain separate.
- Manager revokes stale readiness without inventing a process-exit observation.
  Failure events remain actionable; invalid-twsInfo precedence, generation and
  sequence checks, active-login boundaries and explicit resets remain intact.
- JBR 25.0.4-b508.27 and JavaFX 25.0.4 are fixed selections. Exact JBR build
  metadata and JavaFX module versions are validated. Matching owned payloads
  can be reused offline; mismatches use explicit maintenance. Manual runtimes,
  previous-payload removal, profile format 7 and Manager's bootstrap are retained.
- No launcher identity spoofing, new trading/API connection, credential bypass,
  live installation changes or broker logins were performed.

A separate review found no remaining blocking source issue after the marker,
modal-continuation and exact-build metadata refinements. A canceled or failed
handler attempt deliberately stays latched for that JVM; manual intervention is
required instead of rearming on repeated stale notifications.

## Verification and remaining limits

See [TEST_REPORT.md](TEST_REPORT.md) for executed commands, exact case counts,
unchanged baseline failures and artifact verification. The archived upstream
hashes and complete Java/resource patch replay were checked. Source review and
fixtures do not prove real Windows CMD/Swing/RDP/IBKR behavior. Current Windows
validation and paper-Gateway expiry/2FA acceptance remain required. Earlier 2.2.0
Windows passing logs are historical evidence, not execution of 2.3.0.

See [incident and implementation references](docs/ESTABLISHED_SESSION_EXPIRY_FIX.md).
The following review describes the preceding manager.6 changes.

# Historical scoped compatibility and diagnostics review

## Scope

Reviewed against the latest supplied JavaFX-complete 2.1.0 source, not a rebuilt
older release. Changes are localized to managed runtime selection/validation,
server-rejection reporting, read-only diagnostic collection and tests/documentation.
Windows bootstrap and launch-resource scripts are unchanged. Historical review notes
in docs describe their original releases; TEST_REPORT.md is the current execution record.

## Safety boundaries checked

- Only the exact requested Windows-x64 vanilla JBR25.0.4-b508.27 URL is selected.
  There is no fallback to a moving release; JavaFX stays on its existing stable25 line.
- A prior owned/shared different build is not silently reused, deleted or upgraded.
  Existing transactional removal and reinstall support remain, including failed commits.
- No spoofed launcher values, disabled TLS, ignored available-hash mismatch, remote
  log decryption, password export or generic process termination is introduced.
- Server-rejection detection is exact, bounded, generation-aware, and non-clicking.
  Explicit Stop and a later genuinely new engine retain existing lifecycle behavior.
- Diagnostic work happens only when requested. It uses named local roots and bounded
  files/directories/depth, does not execute native launchers, and reports omissions.
- New broker-log redaction precedes generic command filtering to avoid hiding a
  credential label while leaving its value. Headers, compound hashes, keys, session
  paths, account IDs and secret environment variables have negative tests.
- The selected native-launcher identity strings are evidence only, never interpreted
  as a command or applied to startup. Embedded templates can differ from runtime values.
- Engine revision/cache is advanced to manager.6 for the new handler/event. Logical
  patches reproduce the maintained source; archival upstream/licence bytes are retained.

## Remaining qualification

No Windows/JBR25/IBKR process was run here. Synthetic network/native-launcher/module
fixtures do not prove publisher availability, real server acceptance or exact PE-resource
layout. Pinning returns to the earlier manually successful runtime, but a real login and
locked disconnected-RDP restart remain required. Encrypted application logs still need
Gateway/TWS's export-to-plaintext path. Redaction is best effort; inspect logs for private
trading/account details before sharing.
