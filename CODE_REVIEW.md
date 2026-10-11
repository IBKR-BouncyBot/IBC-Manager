# IBC Manager 2.1.0 - scoped compatibility and diagnostics review

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
