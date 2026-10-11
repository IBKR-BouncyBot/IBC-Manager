# User guide - IBC Manager 2.1.0

Use the root README for installation, profile creation and Windows packaging.
Manager uses Microsoft OpenJDK 25. The optional JetBrains runtime is a separate
choice for the TWS/Gateway process.

## Configuration

Edit Profile has three tabs: Profile, TWS / Gateway settings, and Windows startup.
This is the only configuration editor. Supported non-secret engine options show
their effective value and source. Generated config.ini files are diagnostic output.
Windows startup-task controls are shared by Manager and take effect immediately;
the runtime workaround, by contrast, is only applied when the profile is saved.

Choose IB Gateway or Trader Workstation. Default Live/Paper API ports are 4001/4002
for Gateway and 7496/7497 for TWS. Switching the application or trading mode adjusts
a default port but preserves a custom port. The engine command port is separate.
Use distinct ports and settings directories for concurrent profiles.

TWS discovery, startup, read-only login, server-settings options and main-window
handling are restored. Its Enable API command requires the appropriate session
state and explicit confirmation before changing persistent settings. Read-only
login is not supported for Gateway. FIX and separate IBC installations remain
unsupported.

## Copying a profile

Copy opens an independently editable configuration with a new UUID, unused ports,
a unique name, disabled status and auto-start off. Credentials revert to manual
entry; no encrypted password association is shared. Active processes, recovery
history, tokens, logs and settings-directory files are not copied.

The original settings-directory value remains for review. Select a separate
settings directory before running copies simultaneously. Select encrypted mode
and enter a password when required. Cancelling the copy editor saves nothing.

## Managed headless workaround

The checkbox below IBC Java override explains the JBR 25/JavaFX 25 download and
asks permission. All known sessions using the installation must be stopped. Save
performs setup; Cancel makes no change. A progress window permits cooperative
cancellation before the short commit phase. Unknown or conflicting files are
preserved instead of being overwritten.

Deselect, confirm and Save to restore the previous override and module options.
Shared runtime files remain until the last registered profile releases them.
Manual Java/JavaFX installations are never deleted. Disable and save before
changing the application, installation root or version, or deleting the profile.
See [managed runtime details](MANAGED_RUNTIME.md) for ownership and recovery rules.

The helper does not change Windows locking, RDP configuration or display drivers.
Verify a scheduled restart while RDP is disconnected and Windows remains locked;
success must be recorded before reconnecting. An already-running process merely
surviving disconnection is not that test.

## Runtime controls and diagnostics

Start, Stop, Restart, Pause and Force Stop retain confirmation dialogs. Normal
Stop does not silently force termination. Restart performs a controlled Stop and
fresh Start, not a persistent daily-schedule edit. Start resumes a paused profile.

Green means a verified owned local API listener, not a verified bot/account
session. Specific errors remain visible in Details and Logs. Two-factor approval
remains manual; notification delivery cannot be guaranteed. The failed-start
watchdog retains its overall deadline across child replacements while genuine
login, 2FA and API progress prevent unsafe recovery. Leave Manager running for it.

Logs normally flush in 60-second batches. Diagnostics includes the live buffer
and bounded stderr with best-effort redaction; review before public sharing.
Saved passwords use Windows DPAPI. Runtime configuration may contain the decrypted
password in an owner-restricted file for the launcher's lifetime. Do not publish
these files. See [retained reliability safeguards](V2_0_4_RELIABILITY.md).
