package org.koitharu.kotatsu.core.model

enum class TranslationEngine {
    ML_KIT,
    DEEPL,
    OPENAI;

    companion object {
        val DEFAULT = ML_KIT
    }
}
