# Account reset — 0.26

Open the profile/settings icon and scroll to **Reset account** at the bottom. The confirmation names the data being deleted; Cancel leaves it intact. **Erase & restart** stops listening, cancels screen work and requests an authenticated reset for the current installation.

The backend cancels and awaits that installation's sessions before atomically deleting tasks, facts, turns, task events, retry receipts, pending questions/actions, captured media/conversation text and provenance. Owner-scoped screen caches and queued audio are cleared. Stopped sessions discard late caption callbacks. Other owners' records and sessions are left alone.

After backend success, Android clears the profile, selected languages/voices, conversation settings, local device-action receipts, app volume preference, Screen assist opt-in and in-memory transcript/reply fields, then opens onboarding. The backend connection and installation authentication remain configured so the app can connect and deletion can be retried safely; there is no multi-device login account in this prototype. Android's system permission grants are not revoked by the app.

The deletion endpoint is deliberately not an LLM tool. Only the explicit settings flow offers it. A reset-pending marker resumes an interrupted, previously confirmed reset on the next app open. A failed backend request does not silently clear the profile and leave the user thinking server memory was erased. Repeating deletion is safe. Removal concerns application records, not forensic disk sanitization or data already retained by external providers.

Verification: 82 backend tests passed, including reset authentication, clearing every content table, persistence after reopening SQLite, another owner's records remaining intact, cancellation/cleanup before deletion, retry safety and stopped-session caption rejection. Android build/lint passed. APK 0.26 installed and Docker updated. The phone's settings button, confirmation and Cancel path were inspected. The real user's erase action was not executed; successful deletion was exercised only with isolated test data.

APK: `dist/AnyVoice-0.26.apk`. Rollback source tag: `anyvoice-v0.25-before-account-reset`; APK: `dist/AnyVoice-rollback-before-account-reset.apk`; Docker: `anyvoice-backend:rollback-before-account-reset`. Rolling back software cannot restore deleted memory.
