# Premium AI Translation Overhaul Plan (Status: POLISHED)

## Completed Improvements

### Phase 1: Visual Polish
- [x] **Internal Padding:** Added to prevent text cramping.
- [x] **Stroke/Outline:** White stroke ensuring legibility.
- [x] **Drop Shadow:** Added subtle shadow for depth (Premium Update).
- [x] **Sync:** Fixed "detached" bubble lag by syncing with SSIV state.

### Phase 2: Logic Refinement
- [x] **Vertical Sorting:** Respects Manga reading order.
- [x] **Aggressive Merging:** Increased threshold to 1.1x line height for robust Japanese vertical text handling (Premium Update).
- [x] **Engines:** ML Kit, DeepL, and Groq implemented in `AiFeatureManager.kt`.

## Pending / Future
- [ ] **Diamond Fitting:** Complex text wrapping for oval shapes (Low Priority, current adaptive padding is sufficient).
- [ ] **Settings UI:** The backend supports keys, but UI needs to expose them (Out of scope for this session).
