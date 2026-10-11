> Current 2.1.0 dependency correction: install all eight OpenJFX modules, including
> `jdk.jsobject`. A returned fatal module-resolution error is not an unavailable
> check. Old seven-library installations need disable/Save then enable/Save.
> See [JAVAFX_DEPENDENCY_FIX.md](JAVAFX_DEPENDENCY_FIX.md).

# Optional runtime verification - IBC Manager 2.1.0

## What changed

The reported popup, `Downloaded Java/JavaFX runtime verification failed:`, comes
from the local Java/JavaFX module probe after downloads, not from checksum retrieval.
The old path threw on a timeout/nonzero exit and appended only stderr. A process
returning empty stderr could therefore show the blank explanation in the screenshot,
even if useful diagnostic information was on stdout. The screenshot alone does not
identify that process's exit code or explain why it failed on Windows.

Per the user's request, this probe is no longer a setup gate when a usable result is
unavailable. The optional unavailable-checksum policy from the preceding package is
retained. This remains application version 2.1.0 and engine 3.24.2-manager.5, with profile
format 7. Java version selection, TWS support, copying, authentication and the
user's Windows/remote-access configuration are retained. The dependency correction
also preserves fatal pre-main module errors in the final startup status, rather
than allowing a generic wrapper footer to label them a normal shutdown.

## Decision table

| Check/result | Action |
|---|---|
| Publisher checksum available and valid | Compare it normally. |
| Publisher checksum cannot be downloaded | Continue with the existing per-artifact SKIPPED warning. |
| Runtime probe cannot start or its output cannot be captured | Continue with a SKIPPED warning. |
| Runtime probe denied, timed out or failed without a recognizable fatal module diagnostic | Continue with a SKIPPED warning. |
| Completed failed probe reports a JVM boot-layer, FindException or ResolutionException error | Stop; it has positively identified an unusable module configuration. |
| Runtime probe returned empty or unrecognized inventory output | Continue with a SKIPPED warning. |
| Runtime probe succeeded and lists all eight OpenJFX 25 modules | Record PASSED for module resolution only. |
| Successful inventory lists missing/wrong-version JavaFX modules | Stop; a known compatibility failure is not unavailable evidence. |
| Required runtime file is missing, corrupt or the wrong platform/version | Stop. |
| Available checksum is malformed or mismatched, TLS fails or a redirect is unsafe | Stop. |
| User cancellation or thread interruption | Stop and retain rollback behavior. |

The subprocess still has its 30-second bound. There is no retry loop. A cancelled
operation does not become an optional failure. Unexpected programming exceptions are
not swallowed as unavailable verification.

## Evidence and limits

`payload/download-provenance.txt` records the module probe as PASSED or SKIPPED. For
skipped probes it records the exit code or timeout/execution reason and both stdout
and stderr. Empty streams say `(no output)`. Diagnostic streams are redacted and
bounded; an oversized stream is omitted as a whole rather than cutting a quoted secret.
The warning also reaches setup progress and the application log. A local artifact
hash is not a publisher signature, and a skipped execution test is not a working-runtime
certification. Test normal launch and a secured, disconnected-RDP restart afterwards.

File ownership, sharing, journalled Save/removal, rollback, installed-payload hashes,
manual-runtime preservation, required file checks and build tests are unchanged. The
normal Start-time Java version check also remains: it must not disguise a genuinely
unlaunchable Java executable as an active Gateway session.

## Short confirmation

> Download JBR 25 and JavaFX to fix RDP headless startup?
>
> On Save, Manager downloads the latest stable 25 releases, backs up and updates this
> installation's .vmoptions, and selects JBR. Stop all profiles using it first.
>
> Unavailable checksum or runtime checks are skipped with a warning; files are then not
> fully verified. Detected corruption and unsafe downloads still stop setup.
>
> Disabling restores previous settings and removes Manager-owned files when no profile
> uses them. Manual installations are kept.
>
> Continue?

No changes occur on checkbox selection alone or Cancel. The actual dialog is exercised
by the GUI smoke test. The option retains the label without the old `1050 / ` prefix.

## Replacement

Stop profiles and exit Manager; back up the data directory. Extract this replacement
into a fresh application folder and retry selecting the workaround and Save. Because
the version remains 2.1.0, do not reuse an older built JAR by merging source folders.
Do not manually delete JBR, JavaFX, .vmoptions, or ownership records to apply this update.
