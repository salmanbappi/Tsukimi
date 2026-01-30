# Premium AI Translation Overhaul Plan

This track aims to upgrade the live AI translation feature from a basic functional prototype to a polished, premium experience suitable for Manga reading.

## Problem Analysis
- **Visuals:** Translation text fills the entire speech bubble, looking cramped and "shouting". No margins.
- **Readability:** Text segmentation splits words ("breaks words").
- **Quality:** "Confusing" translations, likely due to poor text ordering or fragmentation of vertical Japanese text.

## Objectives
1.  **Premium UI:** Readable text with proper padding, styling (stroke), and "Manga-like" presentation.
2.  **Smart Processing:** Robust text merging that respects Japanese reading order (Vertical: Top-Right to Bottom-Left).
3.  **Clean Input:** Preprocess text to remove artifacts (newlines/spaces) that confuse the translation model.
4.  **High-Quality Engines:** Support DeepL and OpenAI for premium translation quality (BYOK).

## Implementation Steps

### Phase 1: Visual Polish (The "Premium" Look) [COMPLETED]
- [x] **Padding:** Add internal padding to `AiTranslationOverlayView` so text doesn't touch bubble borders.
- [x] **Stroke/Outline:** Implement a text outline (white stroke, black text) to ensure readability on any background.
- [x] **Font Weight:** Switch to a medium/bold sans-serif typeface for better legibility.
- [x] **Bubble Background:** Ensure the white overlay is slightly transparent or matches the bubble style better (currently solid white).

### Phase 2: Logic Refinement (The "Smart" Brain) [PENDING COMMIT]
- [x] **Vertical Sorting:** Update `AiFeatureManager.kt` to sort merged blocks based on Manga reading order (Right-to-Left columns).
- [x] **Text Cleaning:** Remove newlines and extra spaces from the merged Japanese text before sending to the translator.
- [ ] **Aggressive Merging:** Tune the merge threshold to be more aggressive for vertical text lines.

### Phase 3: Translation Quality (The "Engine" Upgrade)
- [ ] **Settings UI:** Add "Translation Engine" selector (ML Kit, DeepL, OpenAI) and API Key fields in Reader settings.
- [ ] **App Settings:** Update `AppSettings.kt` to store engine preference and API keys.
- [ ] **Engine Architecture:** Refactor `AiFeatureManager.kt` to use a `TranslationEngine` interface.
- [ ] **Implement Engines:**
    - `MLKitEngine` (Existing logic)
    - `DeepLEngine` (HTTP call to `api-free.deepl.com` or `api.deepl.com`)
    - `OpenAIEngine` (HTTP call to `api.openai.com/v1/chat/completions`)

## Technical Details (Phase 3)

### API Integration
- **DeepL:** POST `https://api-free.deepl.com/v2/translate` (Key header `Authorization: DeepL-Auth-Key <key>`)
- **OpenAI:** POST `https://api.openai.com/v1/chat/completions` (Key header `Authorization: Bearer <key>`)
    - Prompt: "Translate the following Japanese manga text to natural English: <text>"

### Dependencies
- Use `OkHttp` for API calls (already in project).
- Use `Kotlinx Serialization` for JSON parsing (already in project).