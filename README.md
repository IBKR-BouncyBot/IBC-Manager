# IBC Manager

**Version 2.0.3 | Windows | IB Gateway**

![IBC Manager main window](images/GUI.png)

*Current application, captured by the GUI smoke test with simulated paper profiles.
No live account or Gateway connection is shown.*

IBC Manager starts, configures and supervises Interactive Brokers **IB Gateway**.
It includes a maintained, source-built IBC engine, so no separate IBC installation
is required. Install the official offline IB Gateway application separately.
TWS and FIX sessions are not supported.

## Features

- One Profile editor for Gateway settings, credentials, authentication, recovery
  and Windows startup.
- Multiple profiles with separate settings directories and API/control ports.
- Start, Stop, Restart, Pause and Force Stop, with confirmation dialogs.
- Windows-protected saved passwords and optional manual password entry.
- Five-minute login retries for unanswered second-factor requests, plus bounded
  recovery when Gateway startup stalls.
- Clear status indicators, live logs and redacted diagnostic exports. Monitoring
  checks listeners without making repeated connections to Gateway's API.

## Getting started

**Windows package:** extract `IBC_Manager_2.0.3_Release_windows.zip`. Run the
installer or open `IBC Manager\IBC Manager.exe` in the portable folder. Keep the
complete portable folder together; its Java runtime and engine are included.

**Java release:** extract `IBC_Manager_2.0.3_Release.zip` and run `run.bat`.
A compatible Java runtime is required. The launcher asks permission before
installing missing prerequisites; it does not remove an existing Java installation.

In the application, create or edit a profile:

1. Select the installed offline Gateway folder, version and settings directory.
   Leave the Java override blank to use Gateway's bundled runtime.
2. Select Paper or Live, enter the username, and choose saved or manual credentials.
3. Set the API port to match Gateway. Give simultaneous profiles distinct API and
   engine-control ports, and separate settings directories.
4. Select the registered second-factor device when needed, save, then press Start.
   Complete any IBKR Mobile approval yourself.

All configuration is in **Edit profile**: **Profile**, **Gateway settings** and
**Windows startup**. The startup-task controls apply to the whole Manager and take
effect immediately. Generated INI files are not an alternative settings editor.

## Status and unattended operation

**Green: Gateway running - API listener available.** Manager has detected the
listener in the managed process tree. Your trading application verifies its own
API connection, account and permissions. Hover over the API row for this detail;
it is not a failed startup check.

Yellow identifies startup, login/2FA, paused sessions, recovery or uncertain status.
Red identifies a stopped Gateway or a condition requiring attention. The text
beside each indicator explains the specific state.

With retry enabled, an unanswered second-factor challenge triggers a local login
retry after five minutes. **Phone approval remains manual**, and receipt of a new
notification depends on Gateway, IBKR and phone connectivity. The retry does not
force-kill Gateway. Missing credentials or unsupported controls stop the retry
with a diagnostic message.

A separate bounded watchdog can recover a startup that makes no login, 2FA or API
progress. Leave Manager running for this watchdog. Closing Manager can leave the
Gateway engine running, but does not leave Manager's watchdog active.

> Test login, scheduled restart, missed approval and recovery on your own Windows
> installation before unattended use. Manager does not place trades, guarantee
> availability, approve authentication requests or replace your bot's safety checks.

## Upgrading and data

Stop profiles, exit the old Manager and back up the complete data directory first.
Use the same Windows account and computer to retain access to encrypted credentials.
The default data directory is `%LOCALAPPDATA%\IBCManager`. Existing settings are
imported when needed; do not run old and new versions against the same data folder.
For a custom folder:

```powershell
.\run.bat --data-dir "D:\IBCManagerData"
```

Saved passwords are encrypted at rest. The engine's active runtime configuration
can contain the decrypted password in an owner-restricted file until the session
wrapper exits. Do not publish data folders or unreviewed diagnostics. See the
[upgrade guide](docs/UPGRADE_GUIDE.md) and [security notes](docs/SECURITY.md).

## Build and test

To build the Windows installer and portable package from source, run:

```powershell
.\package-windows.bat
```

This runs the build and test gates and creates
`dist\IBC_Manager_2.0.3_Release_windows.zip`, containing only the installer and
portable application folder. You do not need to run the other scripts first.

| Script | Purpose |
|---|---|
| `run.bat` | Run Manager; build a missing JAR when using the source archive. |
| `test.bat` | Compile and run the headless tests. |
| `build.bat` | Test and create the normal JAR, release ZIP and source ZIP. |
| `validate-windows.bat` | Run build, headless and GUI checks without creating an installer. |
| `package-windows.bat` | Run the checks and create the native Windows distribution. |

Development scripts clean `build` and `dist` first, so copy outputs you want to
keep. User-facing scripts pause on completion; `run.bat` pauses after the GUI
closes. Set `IBC_MANAGER_NO_PAUSE=1` for noninteractive use. See the
[batch-file guide](docs/BATCH_FILES.md) for details.

## Documentation and maintenance

[User guide](docs/USER_GUIDE.md) | [Status wording](docs/STATUS_WORDING.md) |
[Authentication retry](docs/SECOND_FACTOR_RETRY.md) |
[Windows acceptance](docs/WINDOWS_VALIDATION_CHECKLIST.md) |
[Engine maintenance](engine/MAINTENANCE.md) | [Changelog](CHANGELOG.md)

The engine is built from included IBC-derived source. Its upstream snapshot,
provenance and reviewed patches are kept separately for maintenance. The engine
runs alongside Gateway in a separate process from Manager. This release changes
presentation and documentation, not the engine or authentication/recovery policy.

## Credits

Thank you to **Richard L King (rlktradewright)**, author and long-time maintainer
of IBC, and to **Steven M. Kearns and all upstream contributors**. Their notices
and corresponding source are retained. IBC Manager is unofficial and is not
endorsed by Interactive Brokers or the upstream IBC authors.

## Thank me

- [IBKR referral (get up to $1000 in IBKR stock)](https://ibkr.com/referral/gerrit585)
- Cardano / ADA: `addr1q85w2v474ywzx868s69pghygek3vrhxm69e7c6ysuf28qhv8kmj5wd059grxl82f8h5mtyzl87cvqj8ldv2e0las7tnsdej9ax`
- Midnight / NIGHT: `addr1qyrzra5qhupeleruc3jezmswkfad32h9qz5lxa88ry2egm8686pww4mw030q7jrf05mjc20ez9ya0nyvuvjvs8v36tlsnhr5nd`
- Ethereum / ETH: `0x78bDC85a97e2d87812Cc37e49936102d897B32d1`
- Solana / SOL: `3S69hjpdnkHgsdeBBQwHY9oLjHuqvw8rLzuAC2jc7CUY`
- XRP: `rJfnMVkbCfVUsyyTxWaeE6b3LVFgqasitw`
- Zcash / ZEC: `t1aDkPkv8n8jJiWFtueANZS2b89x1BsHmFq`

## License

IBC Manager is distributed under the **GNU General Public License, version 3**.
See [LICENSE.txt](LICENSE.txt) for the full terms and [NOTICE.txt](NOTICE.txt) for
attribution. The IBC-derived engine retains its upstream **GPL version 3 or later**
notices. The complete source package includes the engine source and build scripts.

The existing licences are unchanged. IB Gateway itself is a separate Interactive
Brokers product and is not included in this distribution.
