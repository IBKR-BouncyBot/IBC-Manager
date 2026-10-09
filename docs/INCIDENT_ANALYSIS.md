# 2.0.2 diagnostic scope and reproduced retry defects

## What the submitted evidence establishes

The uploaded `IBC-Manager-Diagnostics-IB-Gateway-1050-20261007-132104.zip`
has SHA-256 `ed131474bfabb1a8837b841cdabc5deba1b893114fe2af72bb41e573d5bd3e67`.
Its own manifest records Manager 1.0.22, generated UTC 2026-10-07T13:21:04.310053800Z,
Windows 11 and Gateway 1050. It records STARTING, a live process, a command listener
and no API listener. This is the earlier warm-start incident archive. It is not
a trace of the user's reported integrated-engine 2.0.1 second-factor retry test.

The report that the phone notification was dismissed and the desktop remained on
Gateway's login form is accepted as the user's observation. It does not tell us
whether this engine saw the challenge, whether the deadline fired, whether a
Cancel action was attempted, or which login controls remained enabled.

## Independently reproduced defects

The 2.0.1 returned-login adapter required an enabled Log In button before filling
credentials. A cleared password with an associated disabled button reproduced a
failed retry against the original code. The old-target capture also rejected a
registered replacement JFrame. The inline observer depended on an initial LOGIN
heading, and a second field fill could disable an asynchronously validated button.
These paths now have executable engine/Swing regressions and targeted corrections.
The engine still refuses ambiguous controls or missing credentials rather than
blind clicking or force killing. The local deadline cannot guarantee push delivery.

## Verification boundary

The release report/log archive distinguishes headless state-machine tests, actual
engine Swing fixtures and a real-duration 300-second local timer test. None connects
to IBKR, logs in an account, simulates a phone notification delivery, or verifies
Gateway's proprietary implementation. A fresh Diagnostics export from 2.0.1 or
2.0.2 immediately after the missed retry is needed to identify the actual field incident.
Do not publish unredacted account information or password-bearing runtime INI files.

## Configuration changes

The separate duplicate-functionality review supplied the second change set: one
Profile-owned configuration, transactional legacy import, generated-only INI,
removal of raw/base/obsolete UI paths and guarded saves during recovery. These are
not an alternative retry scheduler and do not merge authentication retry with
process-level stalled-start recovery.
