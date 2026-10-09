# Contributing

Keep changes narrowly scoped and add executable regressions. Run build.bat and
validate-windows.bat; the build compiles both Manager and the included engine.
Do not restore external IBC download/selection, TWS/FIX launch support, credential
arguments, raw API health probes, global process termination or unbounded retry.

For engine work preserve upstream provenance, source notices and a reviewable
patch, follow engine/MAINTENANCE.md, and test against real Windows paper Gateway.
CI source/GUI results are not complete live-session acceptance. Publish matching
corresponding source with binary releases. Do not check in generated JAR/EXE,
credentials, diagnostics or user configuration.
