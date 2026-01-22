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
*   **Mark Selected as Read:** Added a new action in the selection menu to mark any number of selected chapters as read simultaneously.
*   **Mark as Read Up to Here:** Quickly update story progress by marking all previous chapters as read with a single click. Now works reliably even in reversed list mode.
*   **Long Press to Preview:** Long-pressing any manga in lists (Search, Explore, etc.) now opens a quick **BottomSheet preview** with details, tags, and reading options.
*   **Decluttered Action Menu:** Removed redundant buttons from the top selection menu to provide a cleaner interface.

### 📖 Reader Experience
*   **Double Tap to Seek:** Double-tapping the left or right side of the reader now jumps **±10 pages** instantly.
*   **Refresh Chapter:** Added a dedicated **Refresh** button in the reader menu to quickly reload content without leaving the viewer.
*   **Reliable Progress Restoration:** Fixed a critical bug where reopening a chapter would reset the reading position. The app now accurately **remembers the exact page and scroll position** where you left off.

### ✨ Visual & Tactile Refinements
*   **Manga Preview Sheet:** Long-pressing any manga in lists now opens a quick **BottomSheet preview**.
*   **Concurrent Download Controls:** Added granular controls in Download Settings to adjust **Parallel Source Downloads** (up to 10) and **Parallel Page Downloads** (up to 15) for maximum performance.
*   **Haptic Feedback:** Added subtle tactile feedback to meaningful actions like clicks, long-presses, and selection mode entry for a more responsive feel.
*   **Cloudflare Bypass 2.0:** Re-engineered the Cloudflare solver with User-Agent synchronization and randomized interaction logic to improve success rates against modern Turnstile challenges.
*   **Smooth Layout Animations:** Lists throughout the app now feature a graceful **"fall down" animation** when loading.
*   **Optimized Auto-Update:** Improved version parsing to handle semantic tags correctly.
*   **Version 3.0.1:** Critical bug fixes for Cloudflare bypass and stability improvements.

### 🎨 Branding
*   **Custom App Icon:** Updated the application launcher icon.
