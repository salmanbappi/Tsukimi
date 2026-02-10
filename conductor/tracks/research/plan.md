# Squad 2: Discovery - Research Notes

## Feature: Vector Recommendation Stub (Completed)

### Status
- [x] **Schema Update:** `MangaVectorEntity` and `MangaVectorDao` created.
- [x] **Database:** `AppDatabase` updated to version 80 to include the new table.

### Concept
We now have a place to store "embeddings".
- **What is it?** A list of 128 or 256 floating point numbers representing the "style" of a manga cover (e.g., [0.1, -0.5, 0.9...]).
- **How to use:** When a user reads *Berserk*, we look up its vector. Then we query the DB for other vectors that are mathematically close (Cosine Similarity).
- **Future:** We will need a lightweight ONNX model (like MobileNetV3) to generate these vectors from cover images on the device.

### Next Steps (Squad 3)
I will now switch to **Squad 3 (Engagement)** to implement **Tsukimi Wrapped (Stats)**.
I will create a stats engine that aggregates reading history into fun metrics ("Total Pages", "Top Genre").
