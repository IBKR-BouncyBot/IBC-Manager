> Historical 1.x incident analysis, retained for context. Current integration and
> release scope are described in ENGINE_INTEGRATION.md and TEST_REPORT.md.

# IB Gateway 10.50 scheduled-restart incident analysis

## Scope

This analysis is based on the diagnostic bundle generated on 2026-10-07 for the
live `IB Gateway 1050` profile. Secrets, account identifiers, and balance data
are intentionally omitted.

## Conclusion

The evidence does not show a normal Gateway crash followed by a completed IBC
recovery. It shows a repeatable **stalled token-based automatic restart**:

1. The old Gateway/IBC child completed the scheduled restart shutdown normally.
2. `StartIBC.bat` found the auto-restart token and launched a replacement IBC
   child on Gateway 10.50's bundled Java 25 runtime.
3. The replacement command server started and IBC reported `Re-starting session`
   and `Starting Gateway`.
4. No login window, second-factor stage, completed login, API listener, child
   exit, wrapper decision, Java exception, or stack trace followed.
5. The replacement IBC process remained alive indefinitely.

The same failure sequence occurred twice. A fresh launch after manual process
cleanup completed normally, which narrows the failure to the warm/token restart
path rather than general Gateway 10.50 startup or stored credentials.

## First occurrence

- 2026-10-06 11:16:30: Gateway opened its Exit Session Setting window.
- 2026-10-06 11:21:00: the Restart in progress dialog opened.
- The IBC child exited with error level 0.
- `StartIBC.bat` found the auto-restart token and reported that authentication
  would not be required.
- 2026-10-06 11:21:07: replacement IBC 3.24.2 started on Java 25.0.2.
- Its command server started on 127.0.0.1:7462.
- IBC reported `Re-starting session` and `Starting Gateway`.
- No later Gateway/login lifecycle milestone was recorded.
- 2026-10-06 12:11:55: the command server accepted a local connection, but the
  log contains no corresponding `received command: STOP` or channel completion.
  This is consistent with the user's normal Stop request connecting to a wedged
  IBC process without obtaining a usable command response.
- After manual process cleanup, a fresh launch at 12:15:33 reached the login
  window at 12:15:38 and completed login at 12:15:55.

## Second occurrence

- 2026-10-06 23:40:30: Gateway opened its Exit Session Setting window.
- 2026-10-06 23:45:00: the Restart in progress dialog opened.
- The old child again exited normally and `StartIBC.bat` found the same class of
  auto-restart token.
- 2026-10-06 23:45:07: replacement IBC 3.24.2 started, its command server became
  ready, and it reported `Re-starting session` followed by `Starting Gateway`.
- No subsequent login or API lifecycle event was recorded through diagnostic
  generation on 2026-10-07 at 13:21:04 UTC.

At diagnostic generation, IBC Manager observed a live wrapper process and an IBC
command listener but no API listener. Version 1.0.22 therefore remained in the
ambiguous `STARTING` state with the message that it was waiting for login.

## Comparison with Gateway 10.45

The prior Gateway 10.45 diagnostic history contains repeated healthy token
restarts. After `Starting Gateway`, the login window generally appeared within
approximately four to seven seconds and login completed shortly afterwards.
That comparison supports treating the Gateway 10.50 sequence as an abnormal
lack of progress rather than a normal long startup.

## What the evidence does not prove

The diagnostic bundle contains no child JVM exception, native crash dump, or
Gateway stack trace after `Starting Gateway`. It therefore does not prove which
internal Gateway 10.50, Java 25, restart-token, or IBC interaction caused the
hang. IBC Manager cannot safely claim an upstream root cause from this evidence.

## IBC Manager 1.0.23 remediation

Version 1.0.23 adds a progress watchdog tied to the actual IBC child generation:

- The timer begins only after IBC reports `Starting Gateway`, `Starting TWS`, or
  the equivalent application command marker.
- Login, second factor, completed login, a verified API listener, child exit,
  wrapper restart/exit, or a replacement IBC generation prevents or resets the
  stall condition.
- Five minutes without any such progress becomes the explicit red
  `STARTUP_STALLED` state.
- The main Session toolbar exposes a prominent, confirmed Force Stop action.
- A failed normal STOP against a stalled command server returns quickly to the
  actionable stalled state instead of waiting through the normal long graceful
  shutdown interval.
- If STOP is acknowledged but the process does not exit, the stalled-session
  wait is limited to 15 seconds before instructing the user to Force Stop.
- Force Stop remains scoped to the exact managed wrapper process tree.

The watchdog does not automatically force-kill or relaunch the profile. A fresh
launch may require manual second-factor authentication, and an automatic loop
could repeatedly interrupt a live account or conceal an upstream regression.

## IBC Manager 1.0.24 unattended fallback

Version 1.0.24 keeps the 1.0.23 child-generation watchdog and adds the bounded
automatic recovery requested for an unattended machine. It does not replace a
healthy token restart. It acts only after the current child has remained without
login, second-factor, completed-login, verified-listener, child-replacement, or
terminal-wrapper progress for the complete five-minute timeout.

The recovery sequence is:

1. Persist and capture a redacted diagnostic snapshot.
2. Send one graceful IBC `STOP` and wait up to 15 seconds.
3. If necessary, terminate only the selected profile's tracked wrapper and
   verified descendants.
4. Verify for up to 30 seconds that the old command and API listeners are gone.
5. Wait a 10-second cooldown.
6. Launch one fresh `StartIBC.bat` wrapper through the normal validated Start
   path.

The fresh start may require a new IBKR Mobile approval. Reaching login or
second-factor is progress and disables the destructive watchdog for that child.
IBC Manager does not generate or submit a second factor. From version 2.0.0,
IBC's native relogin policy uses a 300-second threshold evaluated when Gateway
closes the authentication dialog. It is not an independent timer that guarantees
a new phone notification at five minutes. A native relogin can request another
notification without consuming a destructive stalled-start recovery attempt.
See the 2.0.0 independent review for the actual engine timing analysis.

Loop protection is persisted per profile: only one unresolved recovery is
allowed, and at most two automatic recoveries can begin in a rolling hour. A
fresh replacement that stalls, an occupied old port, failed exact-tree cleanup,
or an unreadable rate-limit record ends in `RECOVERY_FAILED`. The Manager does
not continue cycling. Manual Force Stop remains available and cancels any
in-progress recovery.

This fallback addresses the operational deadlock shown by the diagnostic bundle;
it does not identify or repair the upstream Gateway 10.50 warm-restart defect.
Real Windows paper-account testing through multiple scheduled restarts remains
required before relying on it for a live unattended profile.

### Update for 2.0.1

The historical timing qualification above applies to 2.0.0. The integrated
engine in 2.0.1 adds an independent 300-second challenge timer; see
SECOND_FACTOR_RETRY.md. This does not alter the separate stalled-start recovery
policy or prove resolution of Gateway's internal token-restart hang.
