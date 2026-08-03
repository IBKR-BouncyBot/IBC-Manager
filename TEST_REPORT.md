# IBC Manager 1.0.13 test report

## Release identity

- Application version: **1.0.13**
- IBC compatibility baseline: **3.24.1**
- Validation date: **2026-08-03**
- Runtime target: **Java 17 or newer**
- Compilation policy: `--release 17 -encoding UTF-8 -Xlint:all -Werror`
- External Java runtime dependencies: **none**

## Automated result

| Gate | Result |
|---|---:|
| Production Java source files | **101 compiled** |
| Test Java source files | **24 compiled** |
| Automated test cases | **477 passed** |
| Assertions | **5,484 passed** |
| Failed tests | **0** |
| Skipped tests | **0** |
| Compiler warnings | **0** |
| Java bytecode target | **17 / class-file major 61** |

## Reported runtime symptom

A healthy profile's IBC output repeatedly accumulated:

```text
CommandServer: ControlFrom setting =
CommandServer accepted connection from: /127.0.0.1
Closing command channel
```

The sequence appeared at the manager's two-second status-refresh cadence.

## Root-cause regression

The old controller used `TcpPortProbe` against both the IB API port and IBC
command port during every refresh. Connecting to the IBC command port creates a
real command channel, so IBC correctly logged every health check. Explicit
commands also performed a redundant pre-probe before sending the command.

Version 1.0.13 adds independent gates for:

1. Parsing IBC command-server STARTING, OPEN, and CLOSED lifecycle output.
2. Proving twelve consecutive periodic refreshes create zero command-port
   connections while API TCP monitoring continues.
3. Proving explicit commands and graceful Stop are not preceded by a TCP probe.
4. Proving process reattachment performs no constructor-time connection, allows
   one fallback probe on first refresh when history is unavailable, and never
   repeats it on later refreshes.
5. Rejecting source changes that reference the command port from the periodic
   refresh body or call `portProbe.isOpen` from the command-send path.

## Retained behavior

The tests also verify that:

- IBC's ready/listening output enables command actions without a probe;
- closing one client command channel does not mark the server itself closed;
- command-server shutdown output invalidates readiness;
- a successful command confirms readiness;
- an I/O failure invalidates cached readiness;
- a server-side command rejection propagates while retaining transport
  availability;
- Restart clears login/session hints without discarding the command server that
  remains active;
- launch preflight still rejects an occupied command port before credentials are
  loaded or a temporary runtime config is created;
- API TCP monitoring and its handshake caveat remain unchanged.

## Complete retained coverage

The full suite continues to cover:

- randomized profile/configuration round trips;
- secret detection, DPAPI command handling, redaction, and temporary credential
  lifecycle;
- transactional profile save/delete rollback and startup recovery;
- bounded, strict-decoding, no-follow filesystem operations;
- IBC command deadlines and response bounds;
- exact PID/start-time/fingerprint process ownership and reattachment;
- process-tree cleanup, including descendants created after cooperative shutdown
  begins;
- 60-second application/process log batching and detached relay behavior;
- installation discovery and transactional official-IBC installation;
- Windows prerequisite consent and package-script invariants;
- Swing models, action confirmations, status indicators, and real-window GUI
  smoke;
- deterministic JAR, normal release ZIP, source ZIP, and Windows-archive
  assembly invariants.

## Release gates

The completed cross-platform release procedure performs:

1. three complete clean test/JAR/smoke/distribution builds;
2. hash comparison of deterministic JAR and ZIP artifacts;
3. real-window Swing smoke under Xvfb;
4. extracted-release `--version` and isolated `--headless-smoke` execution;
5. extracted-source clean rebuild, complete retest, GUI smoke, and artifact
   comparison;
6. optional Ant-wrapper compatibility validation;
7. ZIP CRC/path/duplicate, source-cleanliness, CRLF/no-BOM, class-file, module,
   licence, and no-bundled-IBC checks;
8. patch reproduction and SHA-256 manifest generation/verification.

The final gate record and hashes are published separately as
`IBC_Manager_1.0.13_FINAL_VALIDATION.txt` and
`IBC_Manager_1.0.13_SHA256SUMS.txt`.

## Windows-native validation boundary

This environment cannot execute Windows PowerShell 5.1, DPAPI, Task Scheduler,
WinGet/WiX, Windows `jpackage --type exe`, NTFS ACLs, or a real IBKR login. The
corrected source must therefore be run on Windows with `validate-windows.bat`
and `package-windows.bat`. The 1.0.13 acceptance check is that an idle profile
no longer creates a new command-server accepted/closed sequence every two
seconds. One sequence per real command and at most one fallback sequence during
reattachment are normal.
