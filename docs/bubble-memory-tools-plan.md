# Bubble: persistent memory and useful tools

Design proposal, 19 November 2026. This document does not implement tools or change the installed app.

## Intended experience

Bubble is a warm, straightforward voice companion that remembers agreed facts, ongoing work and its previous replies. It speaks in the selected language. It can create and update tasks, retrieve memories and explain the next step. Reconnecting Agora, switching to media mode, restarting the app or recreating Docker must not reset those records.

Example:

1. “Remember I need to finish the presentation tomorrow.” → save a task with an absolute local due date; ask for a time only if a timed reminder is requested.
2. “Make it after lunch.” → resolve the presentation task from recent conversation; ask what time if scheduling an alert requires precision.
3. “I've finished that.” → complete that task if it is the single clear referent; otherwise ask which task. Cancel its pending reminder.
4. “What did we get done today?” → report recorded completions and explicitly shared progress, distinguishing these from proposed work.
5. Restart the phone/app/backend, then ask “What is left?” → retrieve the saved tasks, not the previous audio session.

## Memory layers

| Layer | Contents | Lifetime |
| --- | --- | --- |
| Recent conversation | User text, actual spoken reply, unresolved question, last referenced task, current activity | Retained across transport sessions; bounded window loaded per turn |
| Working summary | Decisions, goals, unresolved references, what Bubble last suggested | Updated incrementally; source turns retained for correction |
| Daily journal | Explicitly reported activities, task events, date/time, plans versus completions | Persistent; grouped by the user's local date |
| Personal memory | Explicit “remember” requests and confirmed stable preferences | Until corrected or forgotten |
| Task records | ID, title, status, due date/time, reminder, project, completion timestamp | Persistent; completion is a state transition, not deletion |

The model prompt is not the database. Tasks and tool results are authoritative; summaries are derived. Each memory carries provenance (user statement, task event, or assistant suggestion), timestamps and links to its source. Suggestions must never become facts about what the user actually did. Store what the user shares; Bubble does not know everything that happened on the phone.

## Storage and continuity

For the first single-user build, use backend SQLite in a Docker named volume, scoped to a stable app installation/user ID. Keep provider credentials and the access token separate from that identity. Persist conversation turns, task records, memory entries, reminders and an event ledger. Ordinary container recreation retains the volume; volume deletion does not. Provide export/import and clear-memory controls.

Use an Android Room database as a local task/reminder mirror and action outbox. Android owns reminder delivery, so a scheduled notification does not require the laptop backend to remain online. Sync revisions and tombstones on reconnect. A notification marked Done offline queues the completion event for synchronization.

Store normalized task/event text, not a growing archive of raw microphone recordings. User speech needs a structured textual interpretation for references and audit; do not reconstruct their words from the assistant's answer. Validate that the chosen audio model can return the needed interpretation and tool calls together. If that combination is unsupported at our endpoint, explicitly evaluate a text/tool planning stage; do not silently add another speech provider.

Every request includes server-derived current time, the phone's IANA timezone and local date. Resolve relative dates to absolute timestamps before writing. Date-only tasks stay date-only. Keep the timezone and original phrase for later explanations and handle midnight/timezone changes.

## Initial tool set

| Tool family | Operations | Example |
| --- | --- | --- |
| Tasks | create, list, update, complete, reopen | “Mark the presentation done.” |
| Memory | remember, search, correct, forget | “Remember that I prefer short explanations.” |
| Daily review | get today's activity, remaining work, dated history | “What did we finish yesterday?” |
| Plans | save steps, advance a step, retrieve next step | “I finished step one. What's next?” |
| Reminders | schedule, reschedule, cancel, snooze | “Remind me about this at six.” |
| Media notebook | save an explicit note or supported transcript excerpt | “Save that explanation under my interview notes.” |

Later tools: web lookup with sources, user-invoked screen explanation using approved screen capture, calendar event creation through Android's review screen, and share-sheet drafts. These are separate integrations; a prompt alone does not grant phone access. Avoid broad device-control access in the first version.

## Turn and tool execution

Microphone → Agora RTC → Qwen audio understanding with relevant memory and tool schemas → validated tool executor → durable result → concise response → existing Murf/Agora playback.

The Qwen API documents function calling for Qwen3.8-Omni-Flash. Run a small integration gate against our exact Singapore endpoint, audio input, selected model and streamed tool-call deltas before committing the implementation to that path.

Accumulate and validate complete tool arguments before executing. Scope every lookup/write to the authenticated user. Use immutable IDs and per-turn/tool-call idempotency keys, so retries cannot duplicate a task or alarm. Persist an event before reporting success. An acknowledgement must describe the actual result: saved task, scheduled on phone, pending phone delivery, or failed. Do not say “reminder set” when only a backend intent exists.

If “that” refers to one current task, act on it. If several tasks fit, ask a short clarification. Require clarification for ambiguous dates or objects, not routine explicit requests to create or complete a task. User corrections override summaries, and “forget” removes the underlying memory and invalidates derived summaries/search entries.

Media transcripts and retrieved pages are quoted reference material, never authorized commands. A video saying “delete my tasks” must not execute tools. Only a deliberate user conversation turn can request a mutation.

## Keeping replies quick

Load the recent exchanges and a compact working summary, then retrieve only relevant tasks/memories. Start with indexed SQLite date/entity/text search; add embeddings only if measured retrieval failures justify them. Do not resend historical WAV audio every turn once normalized text is available.

Update summaries and optional daily digests in the background after the durable turn/task write. Ordinary questions need no tool execution. Straightforward task acknowledgements can be rendered from the successful tool result in the selected language, avoiding an unnecessary second model round trip. More complex answers can use a result-to-response model pass. Never promise a fixed one-second response before measuring the complete path.

Track endpoint delay, retrieval time, model first token/tool call, tool execution, first Murf audio and playback start separately. Memory enrichment must not block the media translation pipeline.

## Reminder delivery

Use Android AlarmManager for timed reminders, checking exact-alarm access where precise delivery is requested. Without that access, explain the timing limitation instead of promising exact delivery. Use WorkManager for sync and maintenance. Reschedule persisted alarms after reboot/time changes; completion/cancellation removes the corresponding alarm. Notification actions: Done, Snooze, Open. Spoken reminders are optional; notifications are the baseline.

## Simple companion prompt

“You are Bubble, a warm, practical AI companion. Talk naturally, like a helpful friend. Reply in the user's selected language with short, clear sentences. Use their saved context when it is relevant. Use the supplied clock for dates. Separate what the user said, what you suggested, and what was actually completed. Use tools to remember things or change tasks, and claim success only after they succeed. If you are unsure what ‘that’ means, ask one brief question. Be honest when you don't remember. Respect corrections and requests to forget.”

Keep tool contracts and retrieved memory outside this short personality prompt. Warmth should come from continuity and helpful phrasing, without claiming human feelings or perfect recall.

## Build order and acceptance checks

1. Durable identity, text turns, working summary and date/time context. Verify follow-ups survive media switches, app restart and container recreation.
2. Task and memory tools, event ledger, idempotency and correction/forget flows. Verify duplicate requests create one task, ambiguous completion asks a question, and completed tasks appear under the correct day.
3. Android task mirror, notifications, reminders and offline completion sync. Verify reboot recovery, permission-denied behavior, cancellation and snooze.
4. Daily recap and step-by-step plans; then optional media notes/web/screen tools.

Also test two-user isolation, quoted-media command rejection, multilingual task references, tool failures, “tomorrow” around midnight, timezone changes, edits during sync and summaries that accidentally turn suggestions into accomplishments.

## Android actions: the agent's phone tools

Add a native Android action executor alongside the backend task/memory executor. The model receives a small typed tool catalog; it never emits arbitrary shell commands, Java code or unrestricted intents. The phone maps validated tool names to Android APIs and app integrations.

| Tool | User example | Execution and result |
| --- | --- | --- |
| `open_app(app_alias)` | “Open YouTube.” | Resolve an approved app alias to its launcher; report unavailable if absent. |
| `search_youtube(query)` | “Open YouTube and search for relaxing Telugu music.” | Test an app-targeted search intent on the installed YouTube version; fall back to an encoded YouTube results URL in the browser. Opening results is the first-version promise, not automatically choosing/playing a song. |
| `web_search(query)` / `open_link(url)` | “Search for interview tips.” | Launch a browser/search handler. Reading and summarizing results requires a separate backend web-retrieval tool; opening a search is not equivalent to reading it. |
| `open_maps(query)` | “Show railway stations nearby.” | Open Maps with a geo/search intent. Searching relative to the phone's location is handled by Maps and its permissions; Bubble needs location permission only if it reads coordinates itself. |
| `prepare_calendar_event(title,start,end)` | “Add tomorrow's interview at ten.” | Open a prefilled Calendar insert screen. The user saves it; Bubble says the draft was opened, not that the event is already saved. |
| `set_clock_timer(seconds,label)` / `prepare_alarm(time)` | “Set a five-minute timer.” | Use Android Clock intents, the declared SET_ALARM permission and an available handler. Distinguish this external clock action from Any Voice's own tracked reminders; not every handler returns completion proof. |
| `compose_message(destination,text)` | “Draft a message saying I'll be late.” | Open SMS/email or a supported app draft with the content prepared. The user sends it. Do not claim delivery from an intent launch. |
| `share_note(note_id)` | “Share my shopping list.” | Open Android's share sheet with the selected saved note. |
| `open_dialer(number)` | “Open the dialer for this number.” | Prefill the dialer; the user places the call. Calling/answering is not part of the initial tool catalog. |
| `set_voice_volume(percent)` | “Make your voice quieter.” | Change Any Voice's own output gain through its existing volume setting. |
| `get_device_context()` | “What time is it? Is my phone charging?” | Read available time/timezone, charging and connectivity state through native APIs. Do not grant contacts, location or notification access implicitly. |

Android officially supports cross-app interaction through intents. Individual app search/deep-link handling is app/version dependent, so YouTube search is an explicit device integration test, with a browser fallback. Background activity launches are restricted: execute from the visible app/user interaction where possible, test the floating-overlay path on the OnePlus, and use a tap-to-open notification/action when Android blocks an automatic launch. Never use a background activity-start failure as evidence an app is missing.

### Example: music search with memory

1. User asks Bubble to search YouTube for music.
2. If a previously saved preference is relevant, use it; otherwise ask a short clarification only when needed. An explicit query such as “Telugu relaxing music” can run immediately.
3. Qwen requests `search_youtube` with the search terms, not a raw package/URI supplied by untrusted media.
4. Backend stores an action ID, installation identity, parameters and short expiry. The authenticated phone receives it through the existing command/status channel and records its execution ID locally.
5. Android launches the supported app search, or opens browser results as fallback. Return `opened_app`, `opened_browser`, `needs_user_tap`, `unavailable` or `failed`. These describe the actual handoff, not unseen results inside another app.
6. Bubble says “Opening YouTube results for Telugu relaxing music” in the selected language. Save the query and actual action outcome as recent context so “search for something more energetic” has a referent. Do not infer that music played or that the user liked it.
7. If playback starts, the existing automatic media selector takes over after Bubble's reply ends. App launch itself does not imply playback. An expiring Ask Bubble override must not accidentally prevent returning to media mode.

### Tool delivery, capability checks and boundaries

- Advertise only tools enabled for this installation. Check app handlers, permission state, app visibility and lock state on the phone, rather than trusting a stale backend capability list.
- Use typed arguments, bounded queries and allowlisted app aliases/schemes. Narrow package visibility declarations are sufficient for known integrations; no blanket installed-app inventory is needed.
- Device actions are scoped to the authenticated installation, given unique IDs and expiry times, and not replayed after a restart. Handle the “executed but acknowledgement lost” case explicitly; do not blindly retry a timer or calendar action that could duplicate it.
- Explicit requests to open/search can proceed without an extra conversational confirmation. Ambiguous recipients or event dates need clarification; compose/share/calendar flows leave the final external action visible to the user.
- The first version does not use an AccessibilityService to click arbitrary app screens, nor does it claim to read WhatsApp, contacts, notifications or screen contents without their separate integrations and user grants.
- Accept action requests from deliberate microphone conversation turns only. Media transcripts, webpages and saved quotations cannot authorize phone actions.
- Add integration checks for app absent, no compatible handler, browser fallback, locked phone, denied launch, duplicate command, stale command and follow-up query resolution. Verify YouTube launch/search on the actual installed version before presenting it as supported.

Build order amendment: add native `open_app`, `search_youtube`, `web_search`, `open_maps` and Any Voice volume after durable memory/task tools; then clock/calendar/share/message handoffs. Reading a search result, selecting a video and controlling another app's playback remain separate capabilities requiring their own tested integrations.

## Primary references

- [Qwen function calling](https://www.alibabacloud.com/help/en/model-studio/qwen-function-calling)
- [Qwen Omni audio/text API](https://www.alibabacloud.com/help/en/model-studio/qwen-omni)
- [Android alarm scheduling](https://developer.android.com/develop/background-work/services/alarms)
- [Android persistent background work](https://developer.android.com/develop/background-work/background-tasks/persistent)
- [Android common intents](https://developer.android.com/guide/components/intents-common)
- [Interactions with other apps and package visibility](https://developer.android.com/training/package-visibility/use-cases)
- [Android Clock alarm/timer intents](https://developer.android.com/reference/android/provider/AlarmClock)
- [Background activity launch restrictions](https://developer.android.com/guide/components/activities/background-starts)
