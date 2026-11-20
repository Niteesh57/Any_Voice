# Any Voice 0.17: screen research

## Use

Screen assist requires both the in-app opt-in and Android's Any Voice screen assist accessibility service. The home screen now shows live readiness. When ready, the setup dialog says Screen assist is ready and Done returns to the app instead of sending the user back to settings.

Enable Any Voice, open the page you want help with, tap Ask Bubble in the floating panel, then ask, for example:

- Explain this screen.
- Scroll and compare these C++ courses for a beginner.
- Which of these visible options fits my budget?

Accessibility permission alone does not start a voice session or continuously analyze screens. Screen assist reads only on an explicit screen-related request. Agora still transports voice and Murf speaks in the selected language.

## Reading and scrolling

The accessibility service reads visible accessible app text and takes an on-demand screenshot on Android 11+. It does not subscribe to content for passive collection: event callbacks are empty; window/content/scroll events keep Android's accessibility cache fresh. Current foreground roots are refreshed before reading. Password fields cause observation to be withheld. On Android 11+ a failed/protected screenshot withholds the screen contents instead of treating accessible text as a workaround. Android 10 has a limited accessible-text path without screenshot support.

An observation includes the app package, nearby text, capture timestamp and a downscaled JPEG (longest side 1280 px). The model gets screenshots as image inputs and accessible text as quoted evidence. Foreground changes, screen lock, disabled access and cancellation are checked around capture. Only native scroll actions are exposed; there is no click, typing, checkout or message-sending tool.

explore_screen defaults to three viewports and accepts at most four per call. A turn has at most six observations, three scrolls and eight tool operations. Repeated content, unavailable scrolling and app changes stop exploration. An app/search launch receives a brief settling interval before research. A loading screen is described as loading or inspected again within the budget, not falsely reported as missing permission. Coverage is limited to the observed views, not an entire website or all results.

Scroll receipts are written before scrolling. Retries return the cached observation instead of scrolling again; if the outcome is uncertain and the cache is gone, the tool reports execution_unknown and requires another observation. A small RAM cache supports acknowledgement retries; it is cleared after research/disable. No screenshot or raw screen text is written into durable action receipts. Conversation replies and clarification questions can summarize findings as ordinary conversation history.

## Clarification and cancellation

ask_user saves a device-scoped question, named choices and original goal. Bubble speaks the question and choices, then accepts the next voice reply. answer_question validates that reply against the pending question ID and option IDs. Questions survive voice reconnects and expire after 30 minutes. cancel_question or Cancel question clears them. Conversation is held while a clarification is pending, while lock still stops the microphone.

Stop screen research cancels pending research. Disable stops the session. Pending screen actions are cancelled on turn/session cleanup, so they cannot replay on the next session. Screen text cannot authorize writes; after a screen observation the executor permits only research, clarification and read-only memory/task operations for that turn. A new explicit user command can request a saved note later.

## Readiness bug fixed

An enabled Android switch, a live service connection and an advertised cloud capability are distinct states. Earlier voice sessions captured the screen_tools boolean at startup and could retain an outdated value. Android's test instrumentation also restarts the app process and can temporarily disconnect its accessibility service.

Android UiAutomation also suppresses other accessibility services by default, so UI hierarchy dumps temporarily disconnected this service during debugging while the Android switch stayed on. Final verification uses screenshots and local service probes instead.

0.17 now separates configured permission from a temporary service reconnect, waits briefly for reconnects before capture, and refreshes authorization with each authenticated phone status heartbeat. The agent reads the current session capability before each question. Setup is available while enabled, the UI displays current readiness, and the agent must inspect a screen before answering a screen question rather than substituting old captions or claiming no access without a tool result.

The debug build includes a local service dump probe that exercises the normal capture path without restarting the service. It prints only metadata (connection, status, app, text/image lengths and hash); it does not print screenshot contents. Scroll/retry probes are restricted to the developer fixture app.

## Verification

- 58 backend tests pass, including bounded exploration, repeated view detection, ephemeral screenshots, injection-side-effect blocking, scoped questions/choices, pending-action cancellation and live capability refresh.
- Android assemble/lint and JVM automatic-mode tests pass.
- Real Qwen audio + image test compared two known fixture images, selected the beginner-friendly modern C++ course based on suitability/practice rather than popularity, and stated coverage. This used simulated phone acknowledgements, not a claim about all third-party apps.
- On the OnePlus, Android reported the service enabled and bound. Non-restarting native probes captured actual app text and JPEGs from Any Voice, YouTube and the developer fixture. The fixture changed from courses A/B to B/C after a real scroll. Replaying the same scroll action returned the identical hash without a second scroll.
- The updated 0.17 APK is installed and Docker is updated. A complete new spoken user research interaction is still a user listening check; native capture/scroll and model vision were verified separately.

## Limits and rollback

No whole-website URL fetching, geolocation lookup, arbitrary app clicking, orders or calls are included. Missing information prompts a question. Some apps expose little accessible content, and protected screens are unavailable. This is a sideloaded prototype; broader accessibility-based autonomous behavior needs separate Google Play policy review before store distribution.

Rollback source: tag anyvoice-before-screen-aware, commit 56fbdfa. APK: dist/AnyVoice-rollback-before-screen.apk. Docker image: anyvoice-backend:rollback-before-screen. Keep the anyvoice-memory volume; the additional question table is backward compatible with 0.16.

Android reference for test-tool suppression: https://developer.android.com/reference/android/app/UiAutomation#FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
