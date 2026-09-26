package com.craftworks.music.data.model

import kotlinx.serialization.Serializable

@Serializable
data class LyricsTranslationRequest(
    val songId: String,
    val targetLang: String = "zh-CN",
    val force: Boolean = false
)

@Serializable
data class LyricsTranslationResponse(
    val songId: String? = null,
    val targetLang: String? = null,
    val engine: String? = null,
    val model: String? = null,
    val updatedAt: String? = null,
    val inlineLrc: String? = null,
    val bilingualLrc: String? = null,
    val combinedLrc: String? = null,
    val lines: List<TranslatedLyricLine>? = null
)

@Serializable
data class TranslatedLyricLine(
    val index: Int = 0,
    val start: Int = 0,
    val end: Int = 0,
    val original: String = "",
    val translation: String = ""
)
