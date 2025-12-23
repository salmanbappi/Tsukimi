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
    sed -i '/const val KEY_WEBVIEW_CLEAR = "webview_clear"/a \    const val KEY_GEMINI_OPTIMIZED = "gemini_optimized"' "$APP_SETTINGS"
    echo "  -> Added KEY_GEMINI_OPTIMIZED to AppSettings.kt"
fi

# 2. Add Preference to pref_about.xml
if grep -q "gemini_optimized" "$PREF_ABOUT"; then
    echo "  -> Preference already exists in pref_about.xml"
else
    # Better insertion for XML
    sed -i '/android:key="app_version"/a \
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
    # Specific fix for the "8org.gradle.daemon" corruption
    sed -i 's/8org.gradle.daemon=true/8/' "$GRADLE_PROPS"
    
    # Ensure file ends with newline before appending
    [ -n "$(tail -c1 "$GRADLE_PROPS")" ] && echo "" >> "$GRADLE_PROPS"
    
    # Use sed to replace or append
    for prop in "org.gradle.daemon=true" "org.gradle.caching=true" "org.gradle.parallel=true"; do
        key=$(echo $prop | cut -d'=' -f1)
        if grep -q "^$key=" "$GRADLE_PROPS"; then
            sed -i "s/^$key=.*/$prop/" "$GRADLE_PROPS"
        else
            echo "$prop" >> "$GRADLE_PROPS"
        fi
    done
fi

echo "✅ Feature Injection Complete"