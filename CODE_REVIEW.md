# IBC Manager 1.0.21 dynamic latest-release review

## Scope

Version 1.0.21 changes the GUI IBC installer from a fixed 3.24.2 release asset to
the latest published full release returned by the official IbcAlpha/IBC GitHub
REST API. The application version changes from 1.0.20 to 1.0.21. No unrelated
runtime supervision, profile, command, credential, logging, or trading-session
behavior was intentionally changed.

## Reviewed implementation

- `GithubLatestIbcReleaseResolver` queries the exact repository
  `/releases/latest` endpoint with the supported GitHub API version and bounded,
  strict UTF-8 metadata handling.
- A dependency-free strict JSON parser rejects malformed input, duplicate keys,
  excessive nesting, invalid escapes, and unsafe numeric forms.
- Drafts and prereleases are rejected even though GitHub's endpoint normally
  excludes them.
- The numeric release version must be at or above 3.24.2. Optional leading `v`
  tags are supported.
- Exactly one `IBCWin-<version>.zip` uploaded asset is required.
- The download URL must be the expected HTTPS GitHub release path with no user
  information, non-default port, query, fragment, or raw-path ambiguity.
- GitHub's asset size and `sha256:` digest are required and independently
  compared with the completed transfer and archive bytes.
- The transactional installer validates the archive-reported version against the
  resolved latest version before activation.
- Installed trees are capability-validated rather than tied to one exact future
  version: version/JAR consistency, required classes, launcher switches, and any
  referenced helper scripts must all pass.
- A different existing non-empty installation is never silently upgraded or
  overwritten; the user must explicitly rename or remove it.
- The installer contains no fixed 3.24.2 asset URL or release-specific checksum.
- The selected IBC JAR's embedded class-file level is converted to its Java feature
  requirement; a future release cannot be started with an older Java runtime merely
  because it is above the 3.24.2 compatibility floor.

## Security tradeoff

A dynamic latest channel necessarily trusts the current official GitHub release
metadata and repository account. The published digest protects transfer and
asset consistency but is not an independently pinned maintainer signature.
Capability validation limits accidental interface breakage, and the installer
fails closed when a future release changes required integration surfaces.

## Result

The implementation and deterministic fixtures cover current/future versions,
malformed metadata, asset ambiguity, URL restrictions, digest/size mismatch,
archive mismatch, existing-version behavior, hostile ZIPs, and transactional
cleanup. Final reproducibility and archive results are recorded in
`TEST_REPORT.md` and the external final-validation record.
