# PodScribe visual system

## Direction
An upbeat, calm listening companion inspired by the supplied wellness-app reference. Use a warm blush canvas, white rounded cards, vivid coral for primary actions, and soft violet for secondary choices. Keep podcast artwork colorful and prominent; keep text and playback controls easy to scan.

## Tokens
- Background: blush `#FFF0EF`; cards: `#FFFFFF`; soft raised surface: `#F3E9EE`.
- Primary/action: coral `#C74436`; secondary: violet `#8B55C8`; warm highlight: `#FFA06F`.
- Text: plum ink `#292331`; supporting text: `#756B78`; outline: `#E3D7DE`.
- Dark mode: charcoal background `#111210`; raised cards `#1D1E1C`; orange `#FF913F`; amber `#FFB36B`; high-contrast warm text `#F5F2ED`.
- Shape: 12dp controls, 18dp compact cards, 24dp primary cards, 30dp hero surfaces.
- Type: 30/26sp headlines; 21/17/15sp titles; 16sp body; 12sp metadata.
- Spacing: 4dp base grid; 20dp screen gutters; 14–22dp card padding; 48dp minimum touch targets.

## Library and new episodes
Use warm page headers, a concise subscription and new-episode summary, a responsive artwork grid, readable two-line titles, labeled sort/refresh actions, and one add action. Empty states pair a friendly icon with a clear next step.

## Podcast details and downloads
Give the podcast identity room to breathe, then show episode rows as generous white cards with artwork, title, date, duration, listening progress, and clearly named actions. Keep download location guidance in a compact banner. Put secondary download actions in an orderly overflow menu.

## Full player and transcript
Keep a dismissible handle and compact episode identity above the transcript. Transcript text is the main reading surface, with active-word emphasis and mode/search actions. Anchor a rounded playback card below with progress and reachable controls. On wide screens cap content width; on compact screens keep transcript and controls full-width.

## Settings
Group Library, Transcription, Keys, and Appearance under clear section titles. Use white cards, a segmented Gemini/On-device selector, compact API-key masks with status, a labeled Model ID field, clear OPML actions, and grouped local-model and transcript controls.

## Interaction and accessibility
Use Material 3 pressed, focused, disabled, loading, empty, and error states. Use 150–250ms transitions and respect reduced-motion settings. Preserve RTL layout direction and mixed-script text, add content descriptions to icon actions, keep touch targets at least 48dp, and retain all existing labels and behavior.

## Current phase
Full-app visual refresh across Library, New episodes, Podcast details, Downloads, Settings, mini player, and full player/transcript. Existing data, strings, and playback/transcription behavior are preserved.
