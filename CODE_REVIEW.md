# IBC Manager 1.0.13 code review and command-server log-noise correction

## Scope

Version 1.0.13 reviews the steady-state runtime monitor after a healthy IBC
session repeatedly added these lines to the profile log approximately every two
seconds:

```text
CommandServer: ControlFrom setting =
CommandServer accepted connection from: /127.0.0.1
Closing command channel
```

The review followed the path from `RuntimeRegistry`'s two-second scheduler,
through `ProfileRuntimeController.refresh()` and `TcpPortProbe`, to IBC's local
command server. It also reviewed explicit command dispatch, process reattachment,
startup preflight, status presentation, and failure recovery.

## Root cause

The runtime registry refreshes profile state every two seconds. In 1.0.12,
every refresh opened a real TCP connection to the configured IBC command port
and immediately closed it. IBC treats that as a client command channel and logs
its acceptance and closure. The connection was only a health probe, but it was
indistinguishable from normal command traffic in IBC output.

The same pattern also occurred twice for explicit commands because the manager
first probed the port and then opened a second connection to send the actual
command.

## Correction

### Passive lifecycle tracking

`IbcLogStateParser` now has an independent command-server lifecycle state:

```text
UNKNOWN -> STARTING -> OPEN -> CLOSED
```

It recognizes IBC's command-server startup, ready/listening, accepted-client,
failed/disabled, and shutdown messages. Closing one client channel does not mark
the server itself closed.

### Quiet periodic refresh

`ProfileRuntimeController.refresh()` no longer references or probes the IBC
command port. It consumes the cached lifecycle state while continuing the
separate configured IB API TCP check. This removes the recurring command-channel
connections without changing the two-second GUI/status update cadence.

### Direct commands

Stop, Restart, Pause, reconnect-data, reconnect-account, and enable-API commands
are sent directly through `IbcCommandClient`. A separate preliminary socket
probe is no longer performed. A successful command confirms the cached server
state; an I/O failure invalidates it. IBC command rejection still leaves the
transport marked available because the server responded.

### Reattachment compatibility

A manager instance can reattach to an already-running IBC process after the
original startup line has moved outside the bounded log tail. In that one case,
the controller permits exactly one fallback TCP probe and caches the outcome.
Subsequent two-second refreshes do not repeat it. Current lifecycle output can
still override the cached result later.

### Preserved launch safety

The one-time occupied-port preflight remains before credential retrieval and
before creation of a password-bearing runtime configuration. This check is a
launch conflict guard, not a steady-state monitor.

### Status and documentation

The Overview tab now says that the command server is **Ready ... (reported by
IBC)** rather than describing it as an actively probed open port. The Commands
tab explains that status monitoring does not create recurring command
connections. Documentation distinguishes normal one-connection-per-command
output from the removed idle polling sequence.

## Security and compatibility assessment

- No changes were made to official IBC, `IBC.jar`, IB Gateway, or TWS.
- No credentials or account data are added to logs or command arguments.
- The command server remains loopback-only by default.
- Launch conflict detection still occurs before encrypted credentials are loaded.
- Explicit commands retain total deadlines and bounded response handling.
- API status remains a TCP-only observation and is not presented as an IB API
  handshake or account validation.
- A process reattachment can create at most one compatibility probe; normal
  startup and steady-state monitoring create none.
- Existing historical log lines are not deleted or rewritten by the upgrade.

## Review result

- **101** production Java files;
- **24** test Java files;
- **477** automated tests;
- **5,484** assertions;
- **0** failures;
- **0** skipped tests;
- **0** compiler warnings with warnings treated as errors;
- Java 17 bytecode and no third-party Java runtime dependency.

The cross-platform source and normal release gates are complete when accompanied
by the final validation record. Windows-native confirmation should leave a
profile idle for at least 30 seconds and verify that the accepted/closed command
sequence no longer repeats. One sequence per real command, and at most one after
reattaching without historical startup output, is expected.
