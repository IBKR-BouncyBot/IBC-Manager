# IBC Manager 2.1.0

Managed headless setup now pins JBR **25.0.4-b508.27**, rather than selecting an
unqualified newer release. All eight OpenJFX25 modules are retained. Existing managed
runtimes from a different build require disable/Save on all owners, then enable/Save;
replacing Manager alone does not replace the runtime. Manual installations remain intact.

An exact invalid twsInfo/OK dialog now reports ERROR rather than generic waiting or
normal exit. The engine leaves the dialog untouched and does not retry or bypass IBKR's
rejection. Pinning returns to the earlier manually successful build; acceptance with
this automated setup still requires a real login and secured disconnected-RDP restart.

Diagnostics now includes bounded redacted accessible launcher/rotated/application/API/
crash logs, runtime provenance/releases/options and read-only launcher identity hints.
Missing, locked, encrypted or size-limited sources are inventoried. Encrypted application
logs still require the application's own plaintext export; no credentials/tokens or raw
native binaries are added.

Manager Java25, TWS support, Copy Profile, optional unavailable checksums/probes, prior
CDN/dependency corrections and restart reliability fixes remain. Engine revision is
3.24.2-manager.6; profile format is 7 and GPL licensing is unchanged.

See TEST_REPORT.md and docs/PINNED_RUNTIME_DIAGNOSTICS.md for executed regression tests,
repair steps, collection limits and remaining native Windows/live Gateway acceptance.
