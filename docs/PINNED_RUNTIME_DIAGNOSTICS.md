# JBR compatibility pin, invalid twsInfo handling and expanded diagnostics

## Scope

Application version remains 2.1.0. Engine revision is 3.24.2-manager.6; profile format 7
and existing Microsoft Java 25 Manager bootstrap are retained. The prior fixes for
checksum availability, exact-artifact CDN redirects, JavaFX's jdk.jsobject dependency,
optional unavailable runtime probes, process-test races and startup supervision remain.

The supplied Gateway application log proves that the managed JBR25.0.4.1 session
received a server rejection after authentication, while the earlier manual JBR25.0.4
and an intervening bundled-Azul session succeeded. It does not identify the rejected
field of twsInfo or establish that JavaFX/package differences are irrelevant.

## Exact runtime selection and repair

Managed setup downloads only `jbr-25.0.4-windows-x64-b508.27.zip` from JetBrains'
approved download service. It never follows a moving latest-release selector or
silently substitutes another build. Published SHA-512 is checked when available;
the user's optional unavailable-checksum policy is preserved. The archive's Java
version must be 25.0.4 and its available build metadata must not contradict b508.27.
JavaFX selection remains the stable 25 line with all eight required modules.

An existing managed installation recorded with a different JBR build cannot be silently
reused or adopted by another profile. It remains removable through the existing safe
workflow, including its last-owner and journal/rollback protections.

1. Stop all sessions using that installation and back up Manager's data directory.
2. Replace Manager using a fresh extracted application folder. Disable the workaround
   and Save for every registered owner of the affected installation.
3. Enable it again and Save. The new setup records JBR 25.0.4-b508.27. Then test login
   and a scheduled restart while RDP is disconnected and Windows is locked.

Replacing Manager alone does not change already-owned runtime files. Do not edit
payload hashes, substitute individual runtime files, delete credentials, or reset
Gateway settings. Manual Java/JavaFX installations and prior overrides are preserved.

Pinning an older release freezes its bug/security fixes too. Qualify an intentional
future update separately; the compatibility pin is not a permanent security guarantee.

## Specific server rejection

A new engine window handler recognizes a displayed `invalidTwsinfo` or `invalid twsInfo`
message with an OK button, including the reported untitled dialog. Its bounded component
walk never reads editable credential controls. It emits one specific event per window,
marks that login attempt failed, and leaves the dialog untouched. No OK click, global
keystroke, process kill, identity spoofing or automatic login resubmission is introduced.

Manager accepts the event only from the current engine generation and also understands
the exact native rejection log lines. The error survives late readiness messages and
the wrapper's Normal exit/code-zero footer. A new explicitly observed engine child
resets that rejected generation; manual actions retain their existing semantics.
The new engine is deployed to a different cache identity; old active caches are not edited.

The earlier IBC identity patch's channel/Install4j flags remain unchanged. Adding copied
TWS identity fields (for example productName=Trader Workstation or launcherId=22) to
Gateway is not justified by the supplied logs. The collector below records available
native identity hints for comparison, without changing execution arguments.

## Diagnostic contents

Existing profile, configuration, validation, Manager log and live-runtime snapshot
entries remain. On export only, additional files are inspected under the configured
settings directory and the resolved offline application directory:

- `launcher.log` and rotated launcher logs, selected before other discovered logs.
- Plaintext TWS/Gateway/API logs, JVM `hs_err_pid*.log` reports and recognized
  gateway/tws-exported-logs*.txt files, including bounded log/session subdirectories.
- Current VM options; managed-runtime download provenance; managed, overridden and
  bundled Java release files; two recent Manager-log rotations.
- The installed `.install4j/i4jparams.conf` javaOptions value when accessible. Only
  this field is selected; built-in XML references are decoded before redaction,
  and unsupported references are omitted without resolving external entities.
- Allowlisted literal identity hints from the corresponding native tws.exe or
  ibgateway.exe. At most 16 MiB is read in chunks, at most 64 strings retained,
  supporting ASCII and UTF-16LE. The EXE is never run, copied or patched. An embedded
  placeholder is not claimed to be an effective value. Unknown values are not guessed.

`application-log-inventory.txt` lists the mappings and omissions. Missing, locked,
unreadable, binary, malformed or unrecognized files cannot abort the other collection;
a failure writing the destination ZIP still fails the export instead of claiming success.
Encrypted `.ibgzenc`/`.ibgenc` and compressed archived logs are listed only. Manager has
no decryption key and does not automate Gateway's export UI. Use the application log
viewer to export plaintext, then attach it or put a recognized export in the settings
folder for the next diagnostic.

## Bounds and privacy

No full-disk scan, network share, raw session-token file, credential store, profile
journal, settings XML, or native binary is included. Discovery visits at most 2,048
entries and 64 directories, at depth 2 within selected log/session folders. At most
16 external text logs are included: 2 MiB per log, with a 16 MiB additional-content
budget. Runtime text details have a 128 KiB limit; native identity and installer
metadata are separately bounded. Current root launcher.log is considered even if
directory discovery hits its cap. Tails omit incomplete first lines and oversized
lines; UTF-8 and BOM-marked UTF-16 logs are supported. Links/reparse/special sources
are refused. Read operations do not acquire a Windows desktop or modify source files.

External content is redacted for passwords, tokens, encryption keys, compound token
hashes, authentication headers, common secret environment variables, usernames, account
IDs, session paths and MAC identifiers. This is best effort, not a guarantee of anonymity.
Logs may contain trading/financial details; shared settings directories can include
other sessions. Review the ZIP before publishing. Encrypted bytes are not included
because they cannot be inspected/redacted safely by Manager.

## Validation boundary

The runtime pin, installer rollback/removal, exact-dialog recognition, generation/error
handling, actual diagnostic ZIP output and GUI Diagnostics callback are regression-tested.
The synthetic native-launcher tests validate read-only extraction, not IBKR's internal
executable format or server behavior. Build-host tests do not authenticate with IBKR.
Real Windows/JBR download, post-2FA acceptance and locked-RDP restart remain target-machine
acceptance checks; no generic server-rejection bypass is claimed.
