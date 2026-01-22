package org.koitharu.kotatsu.core.github

import org.koitharu.kotatsu.parsers.util.digits
import java.util.Locale

data class VersionId(
	val major: Int,
	val minor: Int,
	val build: Int,
	val variantType: String,
	val variantNumber: Int,
) : Comparable<VersionId> {

	override fun compareTo(other: VersionId): Int {
		var diff = major.compareTo(other.major)
		if (diff != 0) {
			return diff
		}
		diff = minor.compareTo(other.minor)
		if (diff != 0) {
			return diff
		}
		diff = build.compareTo(other.build)
		if (diff != 0) {
			return diff
		}
		diff = variantWeight(variantType).compareTo(variantWeight(other.variantType))
		if (diff != 0) {
			return diff
		}
		return variantNumber.compareTo(other.variantNumber)
	}

	private fun variantWeight(variantType: String) = when (variantType.lowercase(Locale.ROOT)) {
		"a", "alpha" -> 1
		"b", "beta" -> 2
		"rc" -> 4
		"" -> 8
		else -> 0
	}
}

val VersionId.isStable: Boolean
	get() = variantType.isEmpty()

fun VersionId(versionName: String): VersionId {
	if (versionName.contains("nightly", ignoreCase = true) ||
		versionName.contains("daily", ignoreCase = true)) {
		// Nightly build
		return VersionId(
			major = 0,
			minor = 0,
			build = versionName.digits().toIntOrNull() ?: 0,
			variantType = "n",
			variantNumber = 0,
		)
	}
	val sanitized = versionName.dropWhile { !it.isDigit() }
	val parts = sanitized.substringBeforeLast('-').split('.')
	val variant = sanitized.substringAfterLast('-', "")
	
	val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
	val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
	val build = parts.getOrNull(2)?.toIntOrNull() ?: 0

	// Special handling for old tags like v71-gemini which should be considered older than 2.1.0
	// If there's only one part and it's large, but the current version has 3 parts, 
	// it's likely an old run-number based tag.
	val finalMajor = if (parts.size == 1 && major > 50) 0 else major
	val finalBuild = if (parts.size == 1 && major > 50) major else build

	return VersionId(
		major = finalMajor,
		minor = minor,
		build = finalBuild,
		variantType = variant.filter(Char::isLetter),
		variantNumber = variant.filter(Char::isDigit).toIntOrNull() ?: 0,
	)
}
