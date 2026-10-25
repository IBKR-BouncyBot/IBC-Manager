# Restart and login modes in IBC Manager 2.3.0

Warm restarts remain supported. Version 2.3.0 extends expired-token recovery to
an already logged-in Gateway that encounters the explicit rejection while
reconnecting. Successful daily warm restarts keep their existing behavior.

This describes the 2.3.0 implementation and documented IBKR behavior. Consult
TEST_REPORT.md for checks actually executed; the description itself does not
establish Windows GUI or live Gateway acceptance.

## What each mode does

| Mode or action | Process/session behavior | Authentication consequence |
| --- | --- | --- |
| Daily warm restart: Gateway's saved **Auto restart** setting, optionally set through `AutoRestartTime` | Gateway closes and creates restart data. The IBC wrapper starts the next Gateway child with `-Drestart` when it finds the descriptor. | Reuses valid session authentication; no new 2FA for a successful token-based restart. Expired or revoked tokens cannot be reused. |
| Scheduled cold restart: `ColdRestartTime` | On Sunday at the configured local time, IBC requests shutdown and creates its cold-restart marker. The wrapper's cold branch starts a new child without `-Drestart`. See the descriptor-precedence detail below. | Runs the ordinary full login flow. Credentials and any 2FA required by IBKR must be supplied. |
| Expired-token recovery: extended in 2.3.0 | The exact message in a `Gateway` or `IBKR Gateway` dialog can trigger recovery during a logged-out warm restart or after an established login. A dedicated expiry marker makes the next child start without `-Drestart`. Established-session shutdown uses a worker and a bounded deadline. | Returns to the ordinary full login flow. It cannot renew an expired token or approve 2FA. No `ColdRestartTime` setting is required. |
| Manager **Restart** button | Graceful **Stop**, wait for the old wrapper to exit, then **Start** a new wrapper. It does not send IBC's native `RESTART` command or change Gateway's saved daily restart time. | Ordinarily a fresh login after normal shutdown. Startup still follows the wrapper's existing descriptor selection; this action does not forcibly delete preserved restart data. |
| Native IBC `RESTART` command, including external IBC | Requests Gateway's own warm restart by setting its auto-restart time to the next minute. For TWS it first tries the File / Restart menu. Manager deliberately does not expose this as its Restart action. | Reuses valid authentication. In Gateway, the changed daily restart time persists unless restored by configuration. |
| **Pause**, then later **Start** | Uses the warm-restart mechanism to save session authentication, but the wrapper exits instead of immediately starting another child. A later Start can consume the saved descriptor. | Can resume without fresh 2FA while the saved token remains valid. Pause does not extend the token's validity. |
| `AutoLogoffTime`, `ClosedownAt`, or **Stop** | Closes the session without requesting an immediate restart. `AutoLogoffTime` is Gateway's daily schedule; `ClosedownAt` is an IBC daily or weekday schedule. | The next ordinary launch requires login unless a usable restart descriptor exists independently. |
| Ordinary network reconnect | Gateway remains running and reconnects to IBKR servers; an API client may separately need to reconnect its socket or restore requests. Manager's Reconnect Data/Account commands invoke Gateway's connection controls. | Reconnection itself is not a cold restart and does not inherently request a fresh login. A separate authentication rejection remains a different condition. |

The Manager **Restart** versus native IBC **RESTART** distinction already
existed in 2.1.0. It is not introduced by the expired-token fix.

## Scheduling defaults and precedence

The bundled configurations leave `AutoRestartTime`, `AutoLogoffTime`, and
`ColdRestartTime` blank.

- If both daily-time fields are blank, IBC leaves Gateway's saved daily
  configuration alone. Blank therefore does **not** mean that Gateway's
  existing automatic restart is disabled. This is why external IBC could
  perform daily warm restarts without those fields being set in its INI file.
- Setting `AutoRestartTime` selects automatic daily restart. Setting only
  `AutoLogoffTime` selects daily logoff. If both are set,
  `AutoRestartTime` wins and IBC logs that it ignored `AutoLogoffTime`.
- These daily fields use `hh:mm AM/PM`, such as `11:45 PM`. Their values are
  written into Gateway's own persistent configuration.
- Blank `ColdRestartTime` means no IBC Sunday cold-restart timer. A value uses
  `HH:mm` in the runtime's local timezone, such as `13:00`. It is independent
  of the daily warm-restart setting. Both can be configured together.
- When `ClosedownAt` and `ColdRestartTime` both produce a pending event, IBC
  schedules the earlier one; an exact tie selects `ClosedownAt` shutdown.

Confirmed expiry uses a separate `TOKENEXPIRED<session-id>` marker. The wrapper
checks that marker before searching for an `autorestart` descriptor, consumes the
marker, clears its restart option and starts a fresh child. This applies to the
explicit expiry-recovery path; ordinary warm restart and Pause/Start retain their
existing descriptor handling.

Before launching that fresh child, the wrapper writes
`TOKENEXPIREDPENDING<session-id>`. An actual successful login clears that exact
guard. If the replacement exits without successful authentication, the guard
prevents the ordinary exit path from falling back to stale warm data. A conflicting
or unconsumable recovery marker, or failure to record the required pending guard,
produces an explicit error. Failure to clear the guard after login is reported for
manual review.

Existing explicit 2FA-timeout and login-display-timeout retry policies are evaluated
separately and run with the cleared restart option. The pending guard is not a new
unconditional limit on every fresh-login retry policy. Do not delete recovery
markers to hide a reported failure; retain the diagnostics for manual review.

The older scheduled `COLDRESTART` branch still follows descriptor lookup. Therefore
a separately configured Sunday cold timer does not promise to delete arbitrary
saved descriptors. No default schedule is changed. See
[the established-session fix](ESTABLISHED_SESSION_EXPIRY_FIX.md) and
[the earlier pre-login fix](EXPIRED_SESSION_TOKEN_FIX.md).

## Weekly authentication and 2FA

IBKR's [Auto Restart Considerations](https://www.ibkrguides.com/traderworkstation/auto-restart-considerations.htm)
states that security tokens are invalidated on Sundays at 01:00 US Eastern
and that the first subsequent login needs authentication. It also documents
possible token revocation at other times. The current
[API reauthentication page](https://www.interactivebrokers.com/docs/tws-api/doc/tws-settings/daily-weekly-reauthentication)
describes the weekly authentication cycle as starting Monday. These pages
use different timing descriptions; neither statement establishes the exact
cause or timing of every individual rejection.

The fix responds to the actual expired-token dialog, not to a guessed weekly
deadline. `ColdRestartTime` remains an optional proactive Sunday schedule;
its local-clock value must account for the relevant timezone and daylight
saving changes. The code does not validate whether it is after the IBKR
cutoff.

For a fresh login, IBC uses the configured credential mode. If credentials
are not available to automation, the user must enter them. Any required
2FA approval remains the user's action. Existing 2FA retry settings govern
missed approval attempts; they do not restore expired warm-restart tokens.
The expiry handler does not act while login or 2FA is in progress. It rechecks
message visibility, session ownership and login generation before claiming one
recovery, and respects a previously claimed Stop or Pause/Restart action. The
handler claim is one-shot: if the queued recovery is canceled or preparation fails,
it is not rearmed automatically within that engine JVM. Manual intervention is
then required. Once recovery has committed, a later Stop retains the existing
stop-in-progress response; the explicit Force Stop action remains separate.

For an established session, recovery dismisses only the verified expiry dialog and
requests graceful close outside the Swing UI thread. If that affected engine JVM
has not exited within 30 seconds, the expiry-specific fallback can terminate that
JVM only while its prepared regular expiry marker remains present, allowing the
wrapper to perform the requested cold start. An unavailable or nonregular marker
is reported as a recovery failure without forcing exit. This does not change
normal Stop into a force-stop policy or terminate unrelated processes.

## Network and API connections are separate

IBKR's [System Message Codes](https://www.interactivebrokers.com/docs/tws-api/doc/error-handling/system-message-codes)
documents Gateway-to-server connectivity loss (`1100`) and recovery with
market-data requests lost (`1101`) or retained (`1102`). Gateway reconnects
after server maintenance. These notifications describe the upstream
connection; they are distinct from restarting Gateway's Java process or
reauthenticating an expired session. A bot's local socket connection to
Gateway is a separate connection, as described in
[Establishing an API connection](https://www.interactivebrokers.com/docs/tws-api/doc/connectivity/establishing-an-api-connection).

Manager's existing startup-stall recovery also remains separate. The stall
detector treats a displayed login or second-factor wait as progress and
does not treat those waits as an ordinary startup stall. The expiry fix
uses the exact dialog and engine state instead of broadening that watchdog.

## Source references and verified scope

Paths below are relative to the source archive. Method names are given so references
remain useful when documentation or nearby code changes line numbers.

| Conclusion | Source location |
| --- | --- |
| Scheduling defaults, formats, saved Gateway behavior and native RESTART side effect | [engine/resources/config.ini](../engine/resources/config.ini); matching [Manager template](../src/main/resources/default-config.ini) |
| Daily restart beats daily logoff | [IbcTws.java](../engine/src/main/java/ibcalpha/ibc/IbcTws.java), `configureAutoLogoffOrRestart()` |
| Sunday/local cold timer and earlier-event selection | [IbcTws.java](../engine/src/main/java/ibcalpha/ibc/IbcTws.java), `getColdRestartTime()` and `startShutdownTimerIfRequired()` |
| Expiry-marker priority, ordinary warm restart and pause handling | [StartIBC.bat](../engine/resources/scripts/StartIBC.bat), exit decisions and `GetAutoRestartOption` |
| Warm launch is identified by nonempty `-Drestart` | [SessionManager.java](../engine/src/main/java/ibcalpha/ibc/SessionManager.java), `startSession()` |
| Manager Restart performs Stop then Start | [ProfileRuntimeController.java](../src/main/java/io/github/ibcmanager/runtime/ProfileRuntimeController.java), `restartSession()` |
| Native RESTART/PAUSE and Gateway scheduling | [CommandDispatcher.java](../engine/src/main/java/ibcalpha/ibc/CommandDispatcher.java); [RestartTask.java](../engine/src/main/java/ibcalpha/ibc/RestartTask.java) |
| Exact expiry recognition and state/generation checks | [ExpiredSessionTokenDialogHandler.java](../engine/src/main/java/ibcalpha/ibc/ExpiredSessionTokenDialogHandler.java) |
| Bounded established-session shutdown | [ExpiredSessionTokenRecovery.java](../engine/src/main/java/ibcalpha/ibc/ExpiredSessionTokenRecovery.java); [StopTask.java](../engine/src/main/java/ibcalpha/ibc/StopTask.java) |
| Manager accepts current established-session expiry and clears old readiness | [IbcLogStateParser.java](../src/main/java/io/github/ibcmanager/runtime/IbcLogStateParser.java), `SESSION_TOKEN_EXPIRED` event handling |
| Login/2FA waits protect against ordinary startup-stall recovery | [StartupStallDetector.java](../src/main/java/io/github/ibcmanager/runtime/StartupStallDetector.java) |

The daily scheduling methods, configuration defaults and Manager Restart action
retain their behavior. The engine revision advances to `3.24.2-manager.8` so this
recovery implementation uses a separate verified engine cache.
