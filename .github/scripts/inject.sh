#!/bin/bash

# Define paths (relative to repo root)
APP_SETTINGS="app/src/main/kotlin/org/koitharu/kotatsu/core/prefs/AppSettings.kt"
PREF_ABOUT="app/src/main/res/xml/pref_about.xml"
ABOUT_FRAGMENT="app/src/main/kotlin/org/koitharu/kotatsu/settings/about/AboutSettingsFragment.kt"
GRADLE_PROPS="gradle.properties"

echo "💉 Injecting Gemini Optimized Features..."

# 1. Add Constant to AppSettings.kt
if grep -q "KEY_GEMINI_OPTIMIZED" "$APP_SETTINGS"; then
    echo "  -> Key already exists in AppSettings.kt"
else
    sed -i '/const val KEY_WEBVIEW_CLEAR = "webview_clear"/a 				const val KEY_GEMINI_OPTIMIZED = "gemini_optimized"' "$APP_SETTINGS"
    echo "  -> Added KEY_GEMINI_OPTIMIZED to AppSettings.kt"
fi

# 2. Add Preference to pref_about.xml
if grep -q "gemini_optimized" "$PREF_ABOUT"; then
    echo "  -> Preference already exists in pref_about.xml"
else
    sed -i '/android:key="app_version"/!b;n;n;n;a \
\
	<Preference\
		android:key="gemini_optimized"\
		android:persistent="false"\
		android:title="Gemini Optimized"\
		android:summary="This build has been automatically optimized by Gemini CLI"\
		app:iconSpaceReserved="false" />' "$PREF_ABOUT"
    echo "  -> Added Gemini Preference to pref_about.xml"
fi

# 3. Add Click Listener to AboutSettingsFragment.kt
if grep -q "AppSettings.KEY_GEMINI_OPTIMIZED" "$ABOUT_FRAGMENT"; then
    echo "  -> Listener already exists in AboutSettingsFragment.kt"
else
    sed -i '/AppSettings.KEY_LINK_WEBLATE -> {/i \
		AppSettings.KEY_GEMINI_OPTIMIZED -> {\
			Snackbar.make(listView, "Gemini AI: This build is optimized for performance!", Snackbar.LENGTH_SHORT).show()\
			true\
		}
' "$ABOUT_FRAGMENT"
    echo "  -> Added Click Listener to AboutSettingsFragment.kt"
fi

echo "🚀 Applying Build Optimizations..."
sed -i "s/sourceCompatibility JavaVersion.VERSION_11/sourceCompatibility JavaVersion.VERSION_17/" "app/build.gradle"
sed -i "s/targetCompatibility JavaVersion.VERSION_11/targetCompatibility JavaVersion.VERSION_17/" "app/build.gradle"
sed -i "s/jvmTarget = JavaVersion.VERSION_11.toString()/jvmTarget = JavaVersion.VERSION_17.toString()/" "app/build.gradle"

if [ -f "$GRADLE_PROPS" ]; then
    grep -q "org.gradle.daemon=true" "$GRADLE_PROPS" || echo "org.gradle.daemon=true" >> "$GRADLE_PROPS"
    grep -q "org.gradle.caching=true" "$GRADLE_PROPS" || echo "org.gradle.caching=true" >> "$GRADLE_PROPS"
    grep -q "org.gradle.parallel=true" "$GRADLE_PROPS" || echo "org.gradle.parallel=true" >> "$GRADLE_PROPS"
fi

echo "✅ Feature Injection Complete"
