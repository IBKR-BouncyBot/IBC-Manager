# IBC Manager 2.0.3 release review

Scope: status wording, removal of the permanent Overview API notice, current GUI
screenshot, GitHub README, supplied Thank me section, unchanged GPL licence.

Baseline: supplied 2.0.2 source, independently rebuilt before modification; 639
headless cases / 8,684 assertions passed on Linux/OpenJDK 21.0.12.1.

Runtime changes are string replacements in ProfileRuntimeController only. No
condition, state assignment, timeout, process call, command, credential operation,
profile codec or listener probe is modified. All maintained engine source,
resources, patches and reference bytes remain identical to 2.0.2. The embedded
engine payload is compared byte-for-byte at the final gate.

StatusIndicator maps all existing states to readable labels while retaining colour
semantics. Overview uses short labels and a diagnostic-code tooltip. The API
qualification remains a tooltip, not a claim of a verified bot/account session.
The sidebar uses escaped two-line text to avoid clipping long status headlines.
The real GUI smoke checks the footer's absence and hover explanation's presence.

README content is based on current code and the supplied BouncyBot README. The
Thank me section is copied byte-for-byte as text; its referral offer is supplied
wording, not a newly verified commercial offer. The BouncyBot noncommercial licence
is not copied. All existing Manager/engine licence texts are retained unchanged.

The screenshot fixture uses the actual MainFrame and demo paper-profile snapshots.
No Gateway launch, real account, credential retrieval or broker API call occurs.
The capture is visibly labelled and documented, and fixture code is test-only.

See TEST_REPORT.md and actual validation logs for final measurements. Linux unit
and GUI fixtures do not constitute native Windows/Gateway acceptance. No claim of
resolved real-world phone notification delivery is made by this display-only release.
