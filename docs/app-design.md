# Any Voice app design — 0.12

Warm ivory (#F6F5EF), forest green (#1D5C49), dark ink (#183C35), pale lime (#D9EDB5) and white surfaces. Layout uses a 4 dp spacing grid, 20–28 dp card radii and 24 dp page margins.

Typography uses Android's native sans-serif family, with explicit 400/500/600/700 weights. Large headings use close tracking; small section labels use open tracking. Android supplies fallback fonts for native language scripts. No downloaded icon font or runtime font request is required.

The Any Voice mark is an original five-bar waveform, reused in the adaptive launcher icon, notification, header, voice card and floating control. Assets live in Android vector resources; `BrandView` renders the animated mark/waveform. Hackathon attribution uses text wordmarks: Build with Agora / Bengaluru 2026 / KNOTiC / Agora × Murf AI, based on the supplied event brief. No official third-party logo was fabricated.

Profile setup collects a display name and optional gender locally. Home centers the chosen language, current voice and one listening action. The transcript is collapsed by default. Demo connection details live under the profile. The voice sheet reads the provider catalog, shows only compatible characters for the selected language, and offers gender filters and short real previews.

Motion: 350 ms entrance fade/rise, 250 ms bottom-sheet entrance, subtle waveform movement, press feedback and 180 ms edge snapping. Wave/press/entrance/snap animation honors system animator settings; Android controls window animation scaling. The floating bubble starts at 52 dp and 78% opacity, expands only on tap, and clamps its position within the display.

Audio routing and numbered playback remain separate from visual controls. Diagnostic Hi/TTS mode, its actions, screen content and old probe icon have been removed. The installed application ID remains stable for updates; internal service/state classes are now ListeningService and ListeningState.
