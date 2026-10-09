# Release gate

- [ ] Strictly compile maintained engine and Manager; run the complete headless suite.
- [ ] Run the real Manager GUI and engine Swing/command scenarios.
- [ ] Verify provenance snapshot and logical patch against supplied upstream.
- [ ] Three clean test/JAR/smoke/dist runs reproduce identical artifacts on one JDK.
- [ ] Execute extracted release and rebuild/retest extracted source byte-for-byte.
- [ ] Audit ZIP CRCs, paths, duplicates, symlinks, source cleanliness and line endings.
- [ ] Confirm compiled engine payload is inside JAR and contains no proprietary classes.
- [ ] Generate release notes, complete source, hashes and raw validation logs together.
- [ ] Record separately which Windows native/live Gateway gates have and have not run.
- [ ] Before production acceptance complete docs/WINDOWS_VALIDATION_CHECKLIST.md.
