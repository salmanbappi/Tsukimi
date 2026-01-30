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

## Implementation Steps

### Phase 1: Visual Polish (The "Premium" Look)
- [ ] **Padding:** Add internal padding to `AiTranslationOverlayView` so text doesn't touch bubble borders.
- [ ] **Stroke/Outline:** Implement a text outline (white stroke, black text) to ensure readability on any background.
- [ ] **Font Weight:** Switch to a medium/bold sans-serif typeface for better legibility.
- [ ] **Bubble Background:** Ensure the white overlay is slightly transparent or matches the bubble style better (currently solid white).

### Phase 2: Logic Refinement (The "Smart" Brain)
- [ ] **Vertical Sorting:** Update `AiFeatureManager.kt` to sort merged blocks based on Manga reading order (Right-to-Left columns).
- [ ] **Text Cleaning:** Remove newlines and extra spaces from the merged Japanese text before sending to the translator.
- [ ] **Aggressive Merging:** Tune the merge threshold to be more aggressive for vertical text lines.

### Phase 3: Translation Quality (Future)
- [ ] Investigate DeepL API integration (optional user key).
- [ ] Experiment with other on-device models if available.
