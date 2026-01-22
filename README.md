# Yukimi (Custom Fork)

This is a customized fork of the Yukimi manga reader, featuring enhanced UX, improved chapter management, and visual refinements.

## 🚀 Key Features & Modifications

This fork includes several enhancements distinguishing it from the original Yukimi:

### 📥 Enhanced Chapter Management
*   **Intuitive Deletion:** Replaced the "Save/Memory" icon with a standard **Trash Bin** icon for downloaded chapters to clearly indicate their status.
*   **Double-Click to Delete:** implemented a safety mechanism where deleting a downloaded chapter requires a **double-click** on the trash icon, preventing accidental deletions.
*   **Instant UI Feedback:** The dustbin icon vanishes immediately upon deletion request, reverting to the download icon instantly without waiting for the background service to finish.
*   **Visual Download Progress:** Added a **Circular Progress Indicator** directly on the button during downloads.
*   **Notification Cleanup:** Removed the intrusive "Download Started" notification for a smoother experience.

### 👆 Smart Selection & Navigation
*   **Range Selection:** Long-pressing a chapter item while another is already selected will automatically **select all chapters in between**, mimicking standard desktop range selection behavior.
*   **Mark as Read Up to Here:** Added a new action in the chapter selection menu to mark all previous chapters as read with a single click.
*   **Long Press to Preview:** Long-pressing any manga in lists (Search, Explore, etc.) now opens a quick preview sheet with details and reading options.
*   **Decluttered Action Menu:** Removed the redundant "Save", "Delete", and "Select Range" buttons from the top selection menu to provide a cleaner interface.

### 📖 Reader Experience
*   **Double Tap to Seek:** Double-tapping the left or right side of the reader now jumps **±10 pages** instantly.
*   **Refresh Chapter:** Added a dedicated **Refresh** button in the reader menu to quickly reload content without leaving the viewer.
*   **Reliable Progress Restoration:** Fixed a critical bug where reopening a chapter would reset the reading position. The app now accurately **remembers the exact page and scroll position** where you left off.

### ✨ Visual & Tactile Refinements
*   **Manga Preview Sheet:** Long-pressing any manga in lists (Search, Explore, etc.) now opens a quick **BottomSheet preview** with details, tags, and reading options.
*   **Concurrent Download Controls:** Added granular controls in Download Settings to adjust **Parallel Source Downloads** (up to 10) and **Parallel Page Downloads** (up to 15) for maximum performance.
*   **Haptic Feedback:** Added subtle tactile feedback to meaningful actions like clicks, long-presses, and selection mode entry for a more responsive feel.
*   **Smooth Layout Animations:** Lists throughout the app (Manga lists, Chapters, Explore) now feature a graceful **"fall down" animation** when loading, providing a more premium feel.
*   **Optimized Auto-Update:** Improved the version parsing logic to support semantic versioning (e.g., `v2.1.0-gemini.x`) from automated builds.
*   **Version 2.1.0:** Rebranded and bumped version to 2.1.0 with various internal optimizations and Material 3 refinements.

### 🎨 Branding
*   **Custom App Icon:** Updated the application launcher icon.
