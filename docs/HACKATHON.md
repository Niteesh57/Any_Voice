# Any Voice — hackathon context

## Project direction

Native Android software that captures permitted app audio, prevents the original media audio from reaching the listener, and eventually speaks a translation in the user's selected language. Stage 1 uses repeated “hi” in place of translation to validate the audio path.

The user reported stage 1 working on their OnePlus 7. This is a user-reported result, alongside the separate emulator checks in [VALIDATION.md](../VALIDATION.md). It does not establish support for every app, voice calls, protected content, or every output device.

## Provided event context

Source: [user-supplied event brief](hackathon-brief.txt), retained here for future development. These details have not been independently verified.

- Event: Build with Agora – Bengaluru 2026.
- Organized by Agora; powered by KNOTiC.
- Format: solo / individual hackathon and submission.
- Venue listed: Polaris School of Technology, DivyaSree Technopark, A3, EPIP Zone, Brookefield, Bengaluru, Karnataka 560066, India.
- Detailed event section lists Saturday, 21 November 2026, 8:30 AM–6:30 PM IST. The pasted listing header says “18 Nov”; this date discrepancy remains unresolved.

The supplied common technology requirements call for Agora Conversational AI, Murf AI Falcon TTS, agentic workflows using tools/APIs, an end-to-end user journey, and a working demonstration.

## Implementation status

| Capability | Status |
| --- | --- |
| Native Android installation | Implemented |
| Playback capture of permitted media/game audio | Implemented |
| Media mute with independent hi voice output | Implemented; user reported working on OnePlus 7 |
| Floating controls, diagnostics, volume restoration | Implemented |
| Speech recognition and translation | Planned |
| Target-language selection | Planned |
| Agora Conversational AI | Not integrated |
| Murf AI Falcon TTS | Not integrated; Android TTS is currently used |
| Agentic tools/actions and complete follow-up journey | Not implemented |
| Universal audio interception / call replacement | Not established; system restrictions apply |

## Next development priorities

1. Record the OnePlus Android/OxygenOS version and reproduce capture/mute behavior across chosen media sources.
2. Add target-language selection and a speech recognition/translation pipeline while preserving the proven audio path.
3. Design and integrate Agora Conversational AI and Murf Falcon TTS, with service credentials kept out of the Android APK and repository.
4. Define a useful tool/action workflow and prepare an end-to-end demonstration matching the provided event requirements.
