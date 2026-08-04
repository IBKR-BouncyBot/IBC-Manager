# IBC Manager 1.0.19 source and IBC 3.24.1 compatibility review

## Scope

This release addresses six findings from a focused comparison between IBC
Manager and the exact official IBC 3.24.1 configuration loader, command channel,
command dispatcher, error-exit path, and `StartIBC.bat` lifecycle messages.

The review covered all IBC Manager production and test sources and the official
IBC paths that the Manager invokes or interprets. IBC remains a separate,
unmodified dependency.

## 1. Full-file Java Properties parser is authoritative

IBC 3.24.1 calls `Properties.load(InputStream)` for `config.ini`. Earlier IBC
Manager releases implemented the same ordinary grammar but still treated their
entry-by-entry formatting scanner as the semantic source of truth. A malformed
final continuation demonstrated that these are not mathematically equivalent for
every possible input.

Version 1.0.19 separates the two concerns:

- `authoritativeSettings` comes from one full-file JDK parser operation;
- the entry scanner retains comments, ordering, physical line count, and line
  endings only;
- `get()` and `activeSettings()` expose the authoritative map;
- a scanner/JDK mismatch is explicit and blocks mutation and persistent output;
- the raw editor offers deliberate canonicalization after user confirmation;
- an external source used only for a runtime copy can be canonicalized without
  modifying the original file;
- cleanup of a stale ambiguous runtime file also uses the authoritative map;
- generated IBC bytes are loaded again by the JDK and must equal the intended
  property map.

The implementation was differentially tested against the JDK on 2,000
deterministic malformed/random inputs.

## 2. Scheduled and normal wrapper exits

`Program has exited` describes an IBC child JVM, not necessarily the end of the
long-lived `StartIBC.bat` supervisor. It can be followed by a restart decision or
by a normal wrapper shutdown.

The parser now records wrapper lifecycle facts separately:

- child exit observed;
- restart pending;
- normal exit confirmed;
- child error exit confirmed;
- current child generation.

A normal `ClosedownAt` sequence ending in the exact `Normal exit` and
`Gateway/TWS finished at` wrapper markers becomes `STOPPED`. It no longer becomes
`IBC exited unexpectedly` merely because no Manager-issued Stop preceded it.
Substring lookalikes such as `Abnormal exit` are deliberately ignored.

## 3. Automatic restart state and exact error wording

The parser recognizes the official wrapper decisions for:

- automatic restart;
- cold restart;
- login-dialog display timeout;
- second-factor completion timeout.

Those states are shown as yellow `RESTARTING`. The listener state from the old
child cannot override this lifecycle observation.

The exact IBC 3.24.1 line `Exiting after error with exit code=` is also
recognized. It does not immediately produce a terminal red state because the
wrapper can still launch another IBC JVM. The final wrapper exit determines
whether the outcome is recovered startup or error.

`StartIBC.bat` uses one generic footer (`Normal exit` followed by
`Gateway/TWS finished at`) for the non-restart path. Version 1.0.19 therefore
retains the earlier IBC error marker as the decisive fact: the generic footer and
a zero wrapper exit code cannot downgrade that failure to a scheduled stop.

## 4. Command reply hardening

The command client now recognizes both:

```text
OK
OK <information>
ERROR
ERROR <information>
```

Official IBC 3.24.1 normally emits a trailing information component, including
an empty-info acknowledgement represented as `OK `, but accepting the bare form
is harmless and forward-compatible. A later `ERROR` continues to override an
earlier `OK ... in progress` acknowledgement.

## 5. Batch-path policy and diagnostics

The Manager intentionally keeps the strict path policy required by official
`StartIBC.bat`. That wrapper re-expands path values through `cmd.exe`; accepting
metacharacters without redesigning the transport would be unsafe.

The shared policy rejects:

```text
" % ! & | < > ^ ( )
```

The validator and launch factory now identify the exact character, enumerate the
complete restriction, and recommend `C:\IBC`, `C:\Jts`, or
`C:\IBKRSettings`. Profile-editor path controls expose the same guidance. A path
under `C:\Program Files (x86)` is deliberately rejected rather than being
silently misquoted.

## 6. Single-backslash diagnostics

Java Properties removes an unrecognized escape backslash. Therefore:

```properties
IbDir=C:\Jts
```

has the semantic value `C:Jts`, while a literal Windows path requires:

```properties
IbDir=C:\\Jts
```

The formatting scanner now records suspicious odd backslash runs in raw values,
and validation adds a warning naming only the setting. Secret values are never
included in the message. Correctly escaped or canonically generated properties
do not produce the warning.

## Security and compatibility assessment

The changes fail closed at the IBC boundary:

- malformed semantic/formatting disagreement cannot be persisted implicitly;
- the original external file is not rewritten by runtime canonicalization;
- no credential is emitted in backslash diagnostics;
- normal child errors are not hidden, but wrapper recovery is allowed to finish
  before a terminal state is chosen;
- command rejection still overrides preliminary acceptance;
- Windows batch restrictions were documented rather than weakened.

No IBC source or JAR modification is required. No TOTP generation was added.
The green API-listener state remains only an operating-system listener
observation, not proof of an IB API handshake, account, permissions, or trading
readiness.

## Release assessment

The Java/cross-platform code and complete local release gate passed, including
deterministic builds, extraction/rebuild, GUI smoke, archive, patch-reproduction,
and checksum verification. Windows-native WiX/jpackage, DPAPI, Task Scheduler,
and real IBC/Gateway lifecycle validation remain separate acceptance checks
documented in the Windows checklist.
