# User guide - IBC Manager 2.0.3

Install the official offline IB Gateway separately. Extract the normal release
and run `run.bat`, or use the native Windows installer/portable application built
from this source. There is no separate IBC install step.

The profile tab has an included-engine revision label and a Gateway installation
detection button. Set the Gateway root/version/settings folder, paper or live
mode, username and credential mode. Select distinct API and command ports for
each simultaneous profile. Leave the Java override empty to use the Gateway
runtime selected by the included StartIBC script. A supplied override is checked
against the chosen Gateway generation. Follow the path field's CMD restrictions.

Saved-password mode uses Windows DPAPI. Manual mode cannot reliably finish
unattended relogins without password entry. All configuration is in the Profile editor. Earlier existing/base and Managed
Config values are imported once into format 6. The old generated config.ini is
not read as a current configuration input. Gateway settings shows generated
values and their source; unknown compatible engine properties remain editable
there, and unused/unsupported controls are hidden. `SecondFactorDevice` chooses a registered device; it is not a TOTP seed.

Start, Stop, Restart, Pause and Force Stop require explicit manual confirmation.
Cancel is the default. Reconnect controls are unavailable before login and main
window readiness. The TWS-only Enable API action has been removed.

Green reads "Gateway running - API listener available" and means an owned API
listener was detected on Windows. The API row tooltip explains that your trading
application verifies its own connection/account. The always-visible disclaimer is
removed. Yellow is pending/paused/uncertain; red means
stopped/error/stalled/failed recovery. Read the explicit state and log detail,
not the color alone. Engine events revoke stale readiness across replacement
JVMs. A command acknowledgement still must be reconciled with the actual
lifecycle; PAUSING is not PAUSED.

With notification retry enabled, the maintained engine arms a 300-second
monotonic timer when it recognizes the second-factor challenge. The deadline
fires even if the challenge window remains open. It clicks one unambiguous
Cancel in that exact window when still visible, restores the ordinary login
fields before waiting for Log In to enable, and submits without duplicate writes.
The recognized form may be reconstructed and registered by the engine; an old
hidden challenge heading is not treated as a visible authentication request. Each newly detected challenge starts a new interval.
Approval cancels pending work; duplicate window events do not extend the timer.
Disabling the option is respected; there is no extra migration or reset of the
stored choice during the profile migration.

No request is approved automatically, and phone delivery is not a real-time
guarantee. Missing credentials, unsupported/ambiguous Cancel controls or an
unavailable login form produce a visible blocked-retry message without killing
Gateway. Manual password mode needs manual action. See SECOND_FACTOR_RETRY.md.

Startup-stall recovery remains separate: after a confirmed five-minute no-progress
startup it can perform exact-tree cleanup and a fresh start within persistent
limits. An observed 2FA challenge is progress, not grounds for that forced cleanup.

Leave Manager running for automatic stalled-start recovery. If closing Manager
while its wrapper remains alive, the engine/relay may continue, but the Manager
watchdog does not. Stop profiles before upgrading, editing their program tree,
or removing engine caches.

The cache under Gateway settings is automatically created from the embedded
payload. Do not edit its scripts or JAR. A checksum failure is a safety error,
not a reason to bypass validation. Stop affected profiles and diagnose package
integrity; recreating an intact cache must not replace an active installation.

Use diagnostic export when reporting problems. It includes the integrated engine
revision and labels obsolete external IBC paths as ignored. See UPGRADE_GUIDE.md
and WINDOWS_VALIDATION_CHECKLIST.md for acceptance and rollback.

## Console tools and acknowledgements

The user-facing run/build/test/validate/package batch files pause on completion,
including error exits. `run.bat` keeps the selected console Java process attached
until the GUI closes. Set `IBC_MANAGER_NO_PAUSE=1` for automation; a defined `CI`
also skips waiting. The original exit code is retained. Internal Gateway wrappers
and scheduled native/JAR startup do not pause. About includes explicit thanks to
Richard L King and the upstream contributors.
