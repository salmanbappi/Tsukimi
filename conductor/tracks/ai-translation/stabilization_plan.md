# AI Translation Stabilization & Quality Plan

This plan aims to resolve the distortion and detection issues in the AI translation feature by reverting to stable rendering and refining the detection logic using industry best practices.

## Phase 1: Stability Reset (Revert & Clean)
- [ ] **Renderer Revert:** Revert `AiTranslationOverlayView` to use rounded rectangles instead of BFS-based Path rendering.
- [ ] **Erasure Reset:** Remove the custom bitmap inpainting logic that caused image distortion. Use solid/adaptive colored rounded backgrounds.
- [ ] **Cleanup:** Remove `inpaintedPatch` and custom `outline` from `TranslatedBlock`.

## Phase 2: Professional Detection (Precision)
- [ ] **Dual-Pass OCR:** Refine the dual-pass strategy.
    - Pass 1: Standard resolution for overall context.
    - Pass 2: High-contrast local crops for small/ stylized text (capturing "...", "!", etc.).
- [ ] **Ink-Aware Merging:** Re-implement robust merging that strictly respects bubble boundaries (Ink Mask) without breaking borders.
- [ ] **Confidence Filtering:** Implement a minimum confidence threshold for ML Kit detections to reduce "phantom" bubbles.

## Phase 3: Premium UI (Typesetting)
- [ ] **Diamond Fitting:** Refine the `StaticLayout` logic to fit text into an oval/diamond shape within the rounded rect.
- [ ] **Adaptive Padding:** Ensure padding scales with bubble size to prevent "cramped" or "shouting" text.
- [ ] **Font Weight:** Keep the automatic Bold/Italic logic for spiky/action bubbles but use stable `Typeface` declarations.

## Technical Improvements
- **Contrast Boosting:** Use a more robust `enhanceForOcr` algorithm based on standard image processing kernels.
- **Memory Safety:** Ensure all temporary bitmaps (crops, contrast-boosted) are explicitly recycled.
