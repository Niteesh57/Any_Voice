# Bubble: durable multi-step jobs and human decisions

Design addition, 20 November 2026. The installed 0.16 remains unchanged. Contact lookup, call placement, call speaker control and profile-choice execution are not implemented by this document.

## Execution pattern

Use a persisted state machine with a bounded plan/execute/observe loop, rather than unconstrained recursion. Qwen proposes the next typed tool action; deterministic code validates its scope, arguments, prerequisites and retry policy. A successful intent handoff is not proof that the user's goal is complete.

Job states: queued, running, waiting_for_user, waiting_for_device, completed, partially_completed, failed, cancelled and expired. A step records its tool, validated arguments, attempt ID, preconditions, observed result, completion evidence and retry count. A pending question records its question ID, stable option IDs and labels, selected option, and expiry.

Before each action, check the saved job/step state. After each action, persist the result before deciding what happens next. On disconnect, resume from the last verified step instead of replaying the original command. Use short active execution windows; a human decision can pause the job durably without keeping a cloud voice session alive. A separate queue owns the job, not the lifetime of an Agora session or Python call stack.

Initial bounds: at most eight tool steps and two retries for a retryable read per execution window. Stop on repeated unchanged evidence, missing capability, cancellation, expiry or a non-retryable failure. A job can ask the user to narrow or continue a longer task; it cannot loop indefinitely.

## Human-in-the-loop interaction

Example: Bubble has two verified, launchable app targets and cannot select one confidently.

1. Persist waiting_for_user with a question: “Normal WhatsApp or WhatsApp Clone?”
2. Show the same named choices in the floating panel and speak them using the selected Murf voice.
3. The user's “first one” resolves only against that question's saved, ordered option list. Map it to a stable target ID, not an absolute screen coordinate.
4. Persist the selection and resume only the pending launch step. Do not rerun earlier completed steps.
5. Ask whether to use that target next time when a persistent preference was not explicitly requested. “Always use normal WhatsApp” directly authorizes saving that preference.

A short reply to a pending question takes precedence over interpreting it as a new unrelated job. Bind the answer to the authenticated installation, job ID, question ID and revision; reject expired/stale answers. If two jobs need input, ask which job rather than applying a reply to both. The user can say cancel or change the choice before the external action has run.

Future human prompts must temporarily preserve conversation ownership while awaiting an answer. The current timed Ask Bubble override is insufficient: it can expire while the user is choosing. Media should not consume or replace the pending answer. This does not mean keeping the microphone active while locked or during a cellular call.

## Preference memory

Store typed preferences, scoped to the installation, such as preferred video app, confirmed app/profile target, or an explicitly assigned local contact alias. Record when/how a preference was obtained and whether the user requested it or accepted it. Do not silently infer a permanent default from one accidental tap or one command. Ask once when uncertain; subsequent clearly matching requests can reuse the confirmed preference.

Revalidate the saved target before each execution: the app may have been uninstalled, its profile disabled, the contact changed, or permission revoked. “Use the other one this time” overrides one job without rewriting the default. “Always use this one” changes the preference. Expose these preferences with correction/forget controls.

## Example: find a C++ playlist

The current search_youtube tool can open results for “C++ beginner playlist.” That completes a request to search, not a request to evaluate all results or play the best playlist.

A later playlist-selection workflow needs a real retrieval/ranking tool that returns playlist IDs, titles and evidence, followed by a supported link launch. If that integration requires an additional API/key, configure it explicitly; current Agora/Murf credentials do not grant YouTube data access. Ask about beginner/advanced or language only when missing preferences materially affect selection. Never claim to have compared playlists when only the search page was opened. Selecting a known playlist by deep link does not require arbitrary screen tapping.

## Example: call Amma with speakerphone

Use native contact/telephony tools rather than searching and tapping through the dialer's screen:

1. Check contact/call capabilities and the current user request.
2. Query matching phone contacts locally using an authorized Contacts provider lookup or an explicitly selected contact. Keep the full address book on the phone; return only the minimum matching options.
3. If there are multiple contacts, numbers or SIM accounts and no usable preference, ask a short question and persist the answer.
4. Resolve the chosen contact reference again immediately before calling, then place the explicitly requested call through supported Android APIs with the required CALL_PHONE permission. Without that capability, open a prefilled dialer and report that a tap is required.
5. For the combined request “call Amma on speaker,” include Android's EXTRA_START_CALL_WITH_SPEAKERPHONE request when initiating the call. Validate actual behavior on this OnePlus before advertising it as reliable.
6. Stop Bubble microphone capture before handing off to the call. Never retry call placement merely because an acknowledgement was lost: that could place a second call. Record an uncertain outcome and wait for user/device evidence instead.

An outgoing speakerphone request is distinct from later controlling an existing cellular call. Reliable ongoing call endpoint control and verification generally need the appropriate InCallService/default-dialer integration; the current floating assistant is not a default dialer. A subsequent voice command during a cellular call also cannot assume Bubble still has microphone access. Do not claim connected, answered or speaker enabled unless the available API supplies that evidence.

These are placement/control workflows, not call-audio translation, call recording or forwarding. Do not execute a real call as part of automated testing without an explicitly designated test call.

## OnePlus normal/Clone chooser limitation

The 0.16 device check confirmed an Android chooser for normal WhatsApp versus WhatsApp Clone. The current open_app tool cannot inspect that chooser or select its buttons; it has no screen-reading/tapping service.

A Bubble-owned voice choice can launch a selected target only if Android exposes an authorized, individually addressable app/profile target to Any Voice. Investigate LauncherApps and the profiles actually accessible on this OnePlus. OEM clone profiles are not guaranteed to be available to ordinary apps. A saved preference cannot create missing cross-profile permissions or silently click the system chooser. If no supported profile-specific launch exists, pause for the user's chooser tap and accurately report that limitation. Do not report the voice-only clone-selection workflow as implemented or proven.

General screen-driven observation/click loops remain the separate deferred feature, with their Android capabilities and distribution restrictions. This job system should use native tools first and never pretend that it can observe another app's screen today.

## Evidence and duplicate protection

Use precise outcomes: preference_saved, contact_resolved, search_requested, app_launch_requested, needs_user_selection, dialer_opened, call_requested, verified_completed and execution_unknown. A step's success criteria depend on its actual API, not the model's confidence.

Persist jobs, questions, preferences and acknowledgements in durable storage. Any action delivery must carry job/step IDs, expiry and installation scope. Reads may retry with backoff; external mutations such as calls must not automatically replay after ambiguous execution. Pending questions and jobs survive transport reconnects, but stale external actions expire and require renewed user intent.

## Implementation sequence

1. Durable job/step/question tables, cancellation, bounded execution and preference tools. Integrate the current task/memory and app/search tools first.
2. A floating choice card and spoken clarification/resume path, with fake-device tests for disconnects, expired replies, duplicate delivery and ambiguous “first one.”
3. Device capability investigation for OnePlus clone/profile launches; retain manual chooser selection when required.
4. Optional contact selection/lookup, dialer handoff, then explicitly authorized call placement/speaker-at-start tests. Evaluate default-dialer integration separately if later in-call control is still required.
5. Playlist retrieval/ranking integration when configured; general screen tapping remains separate.

## Primary Android references

- [Common intents, including phone and app handoffs](https://developer.android.com/guide/components/intents-common)
- [TelecomManager and outgoing speakerphone extra](https://developer.android.com/reference/android/telecom/TelecomManager)
- [InCallService call endpoint control](https://developer.android.com/reference/android/telecom/InCallService)
- [LauncherApps and accessible profiles](https://developer.android.com/reference/android/content/pm/LauncherApps)
