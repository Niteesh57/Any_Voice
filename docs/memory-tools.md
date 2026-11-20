# Any Voice 0.16: memory, tasks and phone actions

## Use

Enable Any Voice and speak while Bubble is in conversation mode (or tap Ask Bubble over media). The existing Agora transport, selected-language reply rule and Murf voice remain in place.

- “Remember that I prefer short explanations.”
- “Remember to finish my presentation tomorrow.”
- “What tasks are left?”
- “I finished that task.” (Bubble asks which one if ambiguous.)
- “Move the presentation to Friday.”
- “What did we finish today?”
- “Forget that preference.”
- “Open WhatsApp.”
- “Open YouTube and search for relaxing Telugu music.”

Tasks & memory on the main screen shows saved tasks and facts. Check/uncheck a task to complete/reopen it. Forget removes a fact and clears conversational copies. Date-only task deadlines do not schedule alarms: reminders, screen tapping and message sending are not included.

## Actual implementation

The phone creates a stable private installation ID/key. The backend currently permits one active voice session globally, even though saved records are isolated per installation. Authenticated calls are scoped to that identity; a guessed ID without its key cannot access its records. A reinstall/data-clear creates a new identity, so it will not automatically recover the old records. There is no account recovery/export UI yet.

SQLite stores normalized user text, replies, tasks, explicit memories, task events and retry receipts. Raw microphone audio is not persisted. The first Qwen audio call uses function calling to return a transcript, a direct reply or a bounded action plan. Typed arguments are validated before execution. Tool requests can make bounded follow-up lookup/action calls, then a result-grounded response is spoken through the existing Murf/Agora output path. Ordinary no-tool questions use the direct reply from that interpretation call.

Recent conversation, saved facts and task IDs are reloaded for each turn; reconnecting RTC or switching media mode does not erase them. Older conversations/facts can be searched. Context is bounded (10 recent turns, 60 recent tasks, 40 recent memories); this is not unlimited perfect recall or a completed long-term summarizer. Daily review uses actual task events plus dated conversation reports; it must not treat suggestions as completed activities. Current time and timezone are supplied separately from the model's assumptions.

Writes use retry receipts; identical operations within a turn reuse their receipt. Completing an already completed task does not create a second completion event. Forget clears conversational copies and cached tool results of the forgotten fact; no promise of forensic disk erasure is made.

## Native actions

Only open_app and search_youtube are exposed. App choices come from the phone's launchable-app list (bounded to 100 entries), with scoped package visibility. There is no arbitrary intent, shell, screen reading or AccessibilityService access.

The backend queues a device-scoped, expiring action. Android stores a receipt before launching, executes on its UI thread and returns an acknowledgement through authenticated polling. Lost acknowledgements retry the stored result, not the app launch. A locked phone returns needs_unlock. Native statuses distinguish requested app/search launch, URL fallback, unavailable handler, failure and uncertain execution. An intent dispatch does not prove another app is visibly open or a video is playing; Bubble is instructed to describe that distinction.

YouTube first receives an app-targeted search intent, then an encoded results URL if no app search handler exists. The installed YouTube version and Android background-launch behavior need on-phone validation. Searching does not automatically select/play a result.

## Persistent Docker data

Use the named volume on every recreation:

```powershell
docker build -t anyvoice-backend:dev backend
docker run -d --name anyvoice-backend --restart unless-stopped --env-file backend/.env -e BUBBLE_MEMORY_PATH=/data/bubble.sqlite3 -v anyvoice-memory:/data -p 8000:8000 anyvoice-backend:dev
```

The running service uses this volume. Deleting the volume deletes its database. SQLite is protected by the Docker host's filesystem access controls; database encryption, backup/export, multi-device accounts and offline phone synchronization are not implemented in this phase. The phone still needs the backend online for voice tools and task/memory review.

## Validation and limits

52 backend tests cover persistence, retries, updates, timezone boundaries, isolated device records, identity proof, scoped session access, action expiry/acknowledgements, fragmented tool arguments, incomplete-stream rejection and follow-ups across new voice clients. Android build/lint pass.

Real Murf-generated voice requests against Qwen saved a preference, created a task, completed it in a later client after database reopen, and requested app/YouTube actions with simulated phone acknowledgements. Measured model/tool response times in this one fixture run were approximately 5.2 s, 5.4 s, 4.4 s and 11.2 s respectively, excluding voice input duration and final spoken playback. These are not latency guarantees. Mutations may require a second model call; this feature adds work rather than promising faster replies.

A native Agora RTC -> Qwen memory tool -> Murf -> queued playback/acknowledgement test completed successfully in about 12.6 seconds from the start of its spoken request to completed Telugu playback. Docker container recreation preserved an isolated test task. On-device verification subsequently passed: installed 0.16, checked memory/task tools using a separate test identity, opened Tasks & memory, and observed YouTube results for the supplied query. WhatsApp opens a normal/Clone chooser on this OnePlus; selecting normal WhatsApp opened its home activity. See VALIDATION.md.

## Rollback

- Source: tag anyvoice-before-memory-tools, commit a8ca5b1 (0.15 plus the design document).
- APK: dist/AnyVoice-rollback-before-memory.apk.
- Docker: anyvoice-backend:rollback-before-memory.

Restore the saved APK and backend image together if reverting. Keep the anyvoice-memory volume; the older image does not need to read it. The new APK is dist/AnyVoice-0.16.apk. Package names and app preferences are unchanged.
