# Squad 5: Content Reliability - Universal Search

## Feature 2: Universal Search (Prototype)

### Status
- [x] **Architecture:** `UniversalSearchManager` scaffolded in `core.search`.
- [ ] **Dependency:** Need to inject `MangaSourceManager` or similar to resolve Source IDs to actual `MangaSource` objects.
- [ ] **Logic:** Parallel search execution with `async/awaitAll` is implemented.
- [ ] **Deduplication:** Basic title normalization logic added.

### Integration Plan
1.  **UI:** Need to add a "Global Search" tab or toggle in the main Search screen.
2.  **ViewModel:** Hook `UniversalSearchManager` into `ExploreViewModel` or `SearchViewModel`.

I am proceeding to analyze `MangaSourceManager` to complete the dependency injection.
