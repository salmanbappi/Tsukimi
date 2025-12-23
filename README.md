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
*   **Decluttered Action Menu:** Removed the redundant "Save", "Delete", and "Select Range" buttons from the top selection menu to provide a cleaner interface.

### 📖 Reader Experience
*   **Reliable Progress Restoration:** Fixed a critical bug where reopening a chapter would reset the reading position. The app now accurately **remembers the exact page and scroll position** where you left off.

### 🎨 Branding
*   **Custom App Icon:** Updated the application launcher icon.
