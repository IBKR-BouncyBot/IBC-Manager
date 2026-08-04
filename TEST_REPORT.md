# IBC Manager 1.0.19 test report

## Release identity

- Application version: **1.0.19**
- IBC compatibility baseline: **3.24.1**
- Java source and bytecode target: **17**
- Production Java source files: **114**
- Test Java source files: **24**

## Result

- Automated test cases: **538 passed**
- Assertions: **8,040 passed**
- Failures: **0**
- Skipped tests: **0**
- Compiler warnings: **0**, with warnings treated as errors

Compilation uses:

```text
javac --release 17 -encoding UTF-8 -g -Xlint:all -Werror
```

## Version 1.0.19 regression coverage

### Authoritative Java Properties semantics

IBC 3.24.1 reads `config.ini` with one full-file
`Properties.load(InputStream)` operation. The release tests therefore compare
IBC Manager's imported byte semantics directly with that JDK parser rather than
with the Manager's formatting scanner.

Coverage includes:

- the final-continuation edge case where a lone backslash plus final CRLF has an
  empty authoritative property map but is not safely decomposable as one
  independent property entry;
- 2,000 deterministic malformed/random ISO-8859-1 byte sequences compared with
  a real `Properties.load(InputStream)` result or rejection;
- explicit mismatch reporting when the authoritative semantic map and
  comment/order-preserving scanner disagree;
- blocked mutation and persistent writing until the user corrects or explicitly
  canonicalizes the ambiguous syntax;
- canonical output reloaded through `Properties.load(InputStream)` before use;
- Unicode, separators, literal backslashes, and trailing-space mechanics;
- warnings for suspicious single Windows backslashes without displaying secret
  content.

### StartIBC lifecycle and normal shutdown

The log/parser/controller tests cover the wrapper and child-JVM distinction used
by official `StartIBC.bat`:

- `Program has exited` remains non-terminal while the wrapper decides what to do;
- automatic restart, cold restart, login-dialog timeout recovery, and
  second-factor timeout recovery enter yellow `RESTARTING`;
- a replacement `Starting IBC with this command:` marker revokes capabilities
  from the prior child generation;
- exact `Normal exit`, `Gateway finished at`, and `TWS finished at` wrapper
  markers permit a zero-code exit to finish as `STOPPED`, including `ClosedownAt`;
- unrelated lines that merely contain the words `normal exit` do not count as a
  normal wrapper marker and cannot mask a crash;
- exact `Exiting after error with exit code=` child output remains non-terminal
  until the wrapper restarts or exits;
- the generic `Normal exit` / `Gateway finished at` footer cannot overwrite a
  previously reported IBC child error, even when the wrapper exits with code zero;
- an unrecovered wrapper exit after an IBC error becomes `ERROR`.

### IBC command replies

The command-client fixture covers:

- exact bare `OK` as completed;
- exact bare `ERROR` as rejected;
- ordinary `OK ...` and `ERROR ...` replies;
- preliminary `OK ... in progress` followed by later success or rejection;
- `OK Goodbye` excluded from command success;
- bounded line, response, and total-deadline handling.

### Windows batch-path diagnostics

Tests enforce the fail-closed `StartIBC.bat` argument policy for:

```text
" % ! & | < > ^ ( )
```

The profile validator and launch-script factory use one shared policy. Tests
verify that the exact offending character is reported, safe locations such as
`C:\IBC` and `C:\Jts` are suggested, and `C:\Program Files (x86)` is rejected
because its parentheses cannot be transported safely through the official batch
wrapper.

## Full release gates

The release gate includes:

- three complete deterministic `clean test jar smoke dist` builds;
- identical JAR and ZIP hashes across repeated builds;
- packaged JAR `--version` validation;
- isolated `--headless-smoke` validation;
- real-window Swing `MainFrame` smoke testing under Xvfb;
- extraction and execution of the normal release ZIP;
- extraction, clean rebuild, full retest, and GUI smoke of the source ZIP;
- byte-for-byte comparison of source-rebuilt JAR and release/source ZIP files;
- optional Ant-wrapper compatibility build where Ant is available;
- patch application to the clean 1.0.18 source and artifact reproduction;
- JAR/ZIP CRC, path, duplicate-entry, and source-cleanliness checks;
- Java 17 class-file major version 61 verification;
- Java module dependency audit;
- Windows `.bat` and `.ps1` CRLF/no-BOM checks;
- SHA-256 manifest generation and verification.

## Native Windows acceptance

The cross-platform release environment cannot execute Windows PowerShell 5.1,
DPAPI, NTFS ACLs, Task Scheduler, WiX, or Windows `jpackage`. The included
Windows validation checklist remains required for:

- `validate-windows.bat` and `package-windows.bat` on Windows;
- private Java 17 installation from a Java 8-only starting point;
- native EXE and `_Release_windows.zip` generation;
- real IBC/IB Gateway paper-account login;
- `ClosedownAt`, automatic restart, cold restart, and timeout recovery against
  the real official wrapper;
- real command replies and account/API verification.
