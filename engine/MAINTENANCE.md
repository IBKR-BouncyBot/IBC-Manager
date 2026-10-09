# Maintaining the included engine

Engine revision 3.24.2-manager.3 derives from supplied IBC 3.24.2. The exact
archive hashes and tag commit are in PROVENANCE.json. Keep engine revisioning
independent from the Manager application version.

1. Work from an explicitly selected upstream revision, never an unreviewed
   runtime 'latest' download. Preserve the old snapshot and review the change.
2. Update the reference snapshot/provenance and verify Windows script/helper
   changes against that revision. The source ZIP retains original reference bytes
   with `engine/upstream/** -text` in .gitattributes.
3. Reapply/review the logical patch. Keep engine changes localized and document
   any intentional Gateway behavior difference. The maintained Java tree uses LF.
4. Keep public event protocol 1 stable or version a changed protocol explicitly.
   Do not send secrets or arbitrary dialog text in lifecycle events.
5. Update both `Version.ENGINE_VERSION` and `engine/resources/engine-version`.
   Update IbcVersionInfo and the upstream version file when the upstream changes.
6. Run the complete build, real engine contract suite, Manager and engine Swing
   GUI tests. Preserve negative tests for an unavailable main window, stale
   generations, runtime credentials and interrupted migration.
7. Reproduce the JAR/ZIPs from an extracted source archive. Run native Windows
   packaging and test a real paper account with at least the supported installed
   Gateway versions. Exercise warm/cold restart, missed phone approval, process
   hang, exact-tree cleanup and multi-profile isolation.
8. Publish the Manager release and complete corresponding source together. Stop
   old profiles before switching engines. Never replace an active engine cache
   in place; the content-addressed revision creates a different cache directory.

Build entry point (from repository root):

```text
java src/build/java/io/github/ibcmanager/build/BuildProject.java clean test jar smoke dist
```

Use `validate-windows.bat` for the GUI gate on Windows. A Linux validation host
may run `xvfb-run -a java .../BuildProject.java gui-smoke`; that does not make Linux
a supported production platform.

The original upstream build.xml is reference material, not this product's build
entry point. Manager's build compiles the maintained tree without proprietary
Gateway classes. Do not restore a direct compile-time dependency on GWClient.

## Reproducing the logical patch

Copy `engine/upstream/src` to a separate review directory, normalize those copied
Java text files to LF without altering any characters, then apply
each patch listed in `engine/patches/series`, in order, from that directory
using `git apply --no-index`. The second patch adds the independent second-factor
deadline, safe GUI retry and related lifecycle guards. The third patch repairs
credential-first returned-login handling, registered replacement frames and heading-free
inline challenge observation. The resulting Java tree must match
`engine/src/main/java` byte-for-byte. Do not normalize or edit the archival
`engine/upstream` copy itself; verify it against UPSTREAM_SHA256.json instead.

## 3.24.2-manager.3 authentication timer contract

`SecondFactorRetry` owns one monotonic deadline per recognized challenge, runs
only bounded readiness checks after expiry, and delegates all UI work to the EDT.
`SecondFactorRetryTarget` binds actions to the same LoginManager/handler and exact
challenge generation. It accepts a reconstructed form only after the existing handler
registers it. It requires an unambiguous visible Cancel control when a challenge is
still visible, restores credentials once on a recognized form and waits at most ten
seconds for the real login button to enable before submitting without rewriting fields. Do not replace those checks with a window dispose,
global keyboard input or a process kill.

Login success, failed state, stop, provider replacement and new attempts cancel
old tasks. The obsolete dialog-close retry/exit scheduler was removed to avoid
double submission and premature exits. A closed prompt alone is not proof of
authentication success. The current profile maps enabled retry to 300 seconds.

Test the actual scheduler and Swing dialog as well as virtual-time boundaries.
`SecondFactorRetryGuiSmoke --real-five-minutes` runs the real 300-second path
using fixture controls. It never connects to IBKR and does not certify push delivery.
