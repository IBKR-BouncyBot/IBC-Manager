# Current verification policy

Checksum unavailability is optional as described below. The Java/JavaFX subprocess
probe is now also optional when it cannot provide a usable result; see
[optional runtime check](OPTIONAL_RUNTIME_CHECK.md). Nonzero exit, timeout, execution/
capture failure and empty/unrecognized output produce a SKIPPED warning, not the old
empty error dialog. A conclusive successful inventory showing wrong/missing JavaFX
modules still fails. The confirmation text is now 77 words (removal: 37 words).

# 2.1.0 download policy: optional unavailable checksum files

## User-requested behavior

The previous downloader required a publisher checksum to be obtained before installing
any JBR/JavaFX artifact. The user reports that checksum downloads still fail and requests
continuation when they cannot be downloaded. This revision changes that behavior; it is
not a claim that the latest diagnostic identifies the exact failing HTTP status. The
October 11 15:49 UTC diagnostic has no current setup-error trace, reports a stopped
profile, and references a missing former manual Java override.

Version remains 2.1.0. This source includes the JetBrains exact-CDN redirect fix, Maven
SHA-256/SHA-1 selection, and the prior Windows process-observation test correction.

## Decision table

| Resource/result | Action |
|---|---|
| JBR SHA-512 available and valid | Compare the ZIP against it. |
| JavaFX SHA-256 available and valid | Compare the JAR; no SHA-1 request. |
| SHA-256 unavailable; SHA-1 available and valid | Compare the JAR against SHA-1. |
| No checksum can be downloaded for this artifact | Continue over approved HTTPS, warning that publisher checksum verification is SKIPPED. |
| A checksum is malformed, empty, oversized, or disagrees with the payload | Stop; no unchecked fallback. |
| TLS validation fails, a redirect is unsafe/unrelated, or redirect budget is exhausted | Stop. |
| Cancel or thread interruption | Stop cooperatively and preserve rollback. |
| Required ZIP/JAR, catalogue, native library, version, or JavaFX module is missing/invalid | Stop. |

Unavailable means a typed HTTP 4xx/5xx response or network-transfer failure (including
DNS/connect/read timeout) for the expected checksum. A JBR checksum may have the same
filename under one of the approved prefix-stripping CDN paths. A status error on a
substituted artifact or arbitrary redirect does not establish unavailability of the
requested sidecar. The HTTPS/host/path allowlist is not widened.

The lower-level downloader now distinguishes network failures from rejected content,
TLS identity validation, cancellation and redirect policy failures. This prevents a
broad catch of every IOException from silently bypassing all verification.

The release catalogue no longer demands a checksum hyperlink, but it still requires
a complete, exact Windows-x64 vanilla JBR25 ZIP link. A checksum-only link or filename
suffix cannot be mistaken for the executable archive.

## Consent and evidence

The permission prompt discloses the lower authenticity assurance. Each skipped artifact
emits a warning to progress and the application logger. The installed
`payload/download-provenance.txt` lists the actual published checksum used, or explicitly
says verification was SKIPPED and records the failed checksum request/status. Each
artifact also gets a local SHA-256 for later integrity audits; this is never described
as proof of publisher authenticity. Existing installed runtimes are not redownloaded
and do not gain retrospective publisher verification.

Runtime probing still occurs before profile/.vmoptions commit. It now may execute a
runtime obtained over approved HTTPS without a publisher checksum, as explicitly
requested. This is less assurance than independently matching the publisher's checksum.
The local payload hashes, owner/refcount checks, journal, staged installation, rollback,
removal safeguards and preservation of manual files remain intact.

## UI wording

Checkbox: `Fix Java 25 headless startup with managed JBR + JavaFX`

Confirmation: `Download JBR 25 and JavaFX to fix RDP headless startup?`

Only the `1050 / ` display prefix is removed. Java/version/architecture selection and
older-installation compatibility checks are unchanged. No headless JVM flag, Windows
unlocking, RDP transfer or NoMachine configuration is introduced.

## Test scope

The added suite exercises the real HTTP redirect/transfer loop, runtime installer and
transactional save/removal, substituting only network replies and execution of the
Windows-specific module probe. It covers all eight artifacts with checksum HTTP errors,
DNS/connect/read/header/body/timeout failures, mixed checked/unchecked artifacts,
persistent warnings/provenance, required-file and module failures, TLS and cancellation,
malformed/mismatched sidecars, shared existing safeguards and original-byte rollback.
Real Swing tests check the new label and the complete consent text before answering.

A direct live checksum connectivity attempt failed DNS resolution on this validation
host. No completed Internet download or Windows/Java25 installation is claimed.
The full release tests still run; no build test was disabled by this request.

## Replacing earlier same-version files

Stop profiles and exit Manager. Back up the data directory. Extract the replacement
Release ZIP into a fresh folder, or use the replacement Source ZIP to rebuild on
Windows. Do not reuse an old JAR or merged build directory because the version remains
2.1.0. The engine revision and profile format are unchanged. Retry Edit Profile ->
workaround -> Save; wait for successful setup before Start. No manual deletion of
Gateway, JavaFX, .vmoptions or runtime ownership files is needed.
