# Verified rollback baseline: Any Voice 0.19

- Source: `707c78a`, tag `anyvoice-v0.19-verified-baseline`.
- APK: `dist/verified-0.19/AnyVoice-0.19.apk`.
- APK SHA-256: `91cfb318cc94d75823c49af24e2831ec50c59d1b77fde9811086d2263ad3ba26`.
- Docker image: `anyvoice-backend:verified-0.19`.
- Verification: 66 backend regressions, Android debug build/lint, and native OnePlus synthetic media → Bubble → media round trip retaining session identity and waiting for actual playback acknowledgments. See the dated 0.19 entry in VALIDATION.md for exact coverage and final small fixes.
- This baseline predates bidirectional translation. Its existing eight-column media table stays compatible with the new separate provenance table; no destructive migration is needed to revert.

Verified means these named checks passed. It does not mean every device, language, acoustic condition or overlapping conversation has been validated.
