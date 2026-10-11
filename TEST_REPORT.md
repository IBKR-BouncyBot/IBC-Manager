# IBC Manager 2.1.0 - pinned runtime and diagnostics validation

## Scope and baseline

Input: the latest JavaFX-complete 2.1.0 Source ZIP, SHA-256
`ac7ae9bcfdff71f0f6685a1e64ae9a37472bab984f431d9ae0253bb8d4df4ef3`.
The baseline was compiled and tested: 973 named cases / 27,426 assertions passed.
This update pins JBR25.0.4-b508.27, adds specific invalid twsInfo rejection reporting
and bounded read-only external diagnostics. Engine revision is 3.24.2-manager.6.

## New coverage

Runtime tests check the exact coordinates, no latest-release scan/substitution,
rejecting mismatched downloaded version/build metadata, no silent reuse of a
non-pinned owned/shared installation, and safe removal followed by a pinned install.
All prior optional checksum/probe, JavaFX graph, CDN, transaction and recovery cases remain.

Rejection tests use incident-derived error lines plus validated current/stale engine
events. They check error precedence over normal/zero-code shutdown, no false success,
no blind relaunch, and recovery only through a new observed generation. The actual
Swing engine test checks the untitled exact-message/OK dialog, unrelated dialogs,
HTML whitespace, repeat notifications, no button clicks, failed login state and
registration in the real handler list.

Diagnostic tests exercise the actual ZIP export: current/rotated/child-directory logs,
TWS/Gateway layouts, missing/non-text/locked-access equivalents, count/byte/depth bounds,
source immutability, link isolation, UTF-8/UTF-16, partial/oversized lines, compound
credential fields and crash-log environment secrets, and runtime/options/provenance.
Native EXE identity fixtures cover ASCII, UTF-16LE alignment and chunk boundaries;
installer metadata fixtures check decoding-before-redaction and unsupported-entity
omission. Only allowlisted information is reported, never applied as startup arguments.
The real Manager GUI Diagnostics callback exports a buffer-only marker and a redacted
launcher.log fixture. Consent shows the exact JBR pin.

One intermediate regression exposed a redaction ordering issue when a secret value
ended in '-TOKEN'; the generic filter could consume the following Authorization label.
Sensitive external fields are now masked before generic command-argument filtering;
the negative secret-disclosure test remains in the final suite.

## Executed gates and reports

Final counts and per-run hashes are in the accompanying FINAL_VALIDATION record and
VALIDATION_LOGS.zip, derived from completed runs. The release process performs strict
compilation, complete headless tests, packaged Manager/engine/2FA GUI tests, three clean
builds, extracted-source rebuild/retest and source/engine patch reproduction. No compiler
warnings are accepted. The prior same-version main-window screenshot is unchanged;
it remains explicitly labelled simulated paper data, not live broker evidence.

## Limits

Host: Linux/OpenJDK21.0.12.1, compilation target Java17 using
`--release 17 -encoding UTF-8 -g -Xlint:all -Werror`. Windows launch/build scripts
still select Microsoft Java25. Native Windows-conditional subchecks were not run
here. No Windows EXE/WiX/DPAPI/Task Scheduler operation, real JBR25/JavaFX native
execution, RDP session, authentication or trading action was performed.

Publisher URLs were verified in the official release listing; direct downloads from
the build host failed DNS resolution. HTTP, installed-runtime and native-launcher
bytes in automated tests are fixtures, not completed Internet installation or IBKR
compatibility proof. The pin is the requested return to the previously successful
manual JBR build; it does not identify the server's rejected field or certify the
new automated combination. The identity example was not applied to runtime arguments.
Encrypted Gateway logs are not automatically decrypted or included unredacted.
