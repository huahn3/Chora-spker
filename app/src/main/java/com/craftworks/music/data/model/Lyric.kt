package com.craftworks.music.data.model

import android.util.Log
import androidx.compose.runtime.Stable
import kotlinx.serialization.Serializable
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

// Universal Lyric object
@Stable
data class Lyric(
    val startMs: Int,
    val text: List<String>,
    val words: List<SyncedWord>? = null,
    val endMs: Int? = null
)
@Stable
data class SyncedWord(
    val text: String,
    val startMs: Int,
    val endMs: Int?
)

// LRCLIB Lyrics
@Serializable
data class LrcLibLyrics(
    val id: Int,
    val instrumental: Boolean,
    val plainLyrics: String? = "",
    val syncedLyrics: String? = "",
    val lyricsfile: String? = "",
)

// NetEase Lyrics
@Serializable
data class NeteaseLyricsResponse(
    val pureMusic: Boolean? = false,
    val lrc: NeteaseLrc? = null,
    val tlyric: NeteaseLrc? = null   // translation, may be absent or empty
)
@Serializable
data class NeteaseLrc(
    val lyric: String? = null
)

//region Convert lyric format to app format.
fun MediaData.PlainLyrics.toLyric(): Lyric {
    return Lyric(
        startMs = -1,
        text = if (value.isBlank()) emptyList() else listOf(value)
    )
}

fun MediaData.StructuredLyrics.toLyrics(): List<Lyric> {
    val lyricOffset = offset ?: 0

    // Unsynced
    if (!synced) {
        return listOf(
            Lyric(
                startMs = -1,
                text = line.map { it.value }
            )
        )
    }

    // V2
    if (!cueLine.isNullOrEmpty()) {
        return cueLine.map { cLine ->
            Lyric(
                startMs = cLine.start + lyricOffset,
                endMs = cLine.end + lyricOffset,
                text = listOf(cLine.value),
                words = cLine.cue.mapIndexed { index, cue ->
                    val lineBytes = cLine.value.toByteArray(Charsets.UTF_8)
                    val cueByteEnd = cLine.cue.getOrNull(index + 1)?.byteStart?.minus(1) ?: cue.byteEnd
                    val cueBytes = lineBytes.sliceArray(cue.byteStart..cueByteEnd)

                    SyncedWord(
                        text = String(cueBytes, Charsets.UTF_8),
                        startMs = cue.start + lyricOffset,
                        endMs = cue.end?.plus(lyricOffset)
                    )
                }
            )
        }.sortedBy { it.startMs }
    }

    // V1
    return line
        .groupBy { (it.start ?: 0) + lyricOffset }
        .map { (timestamp, lines) ->
            Lyric(
                startMs = timestamp,
                text = lines.map { it.value }
            )
        }
        .sortedBy { it.startMs }
}

fun LrcLibLyrics.toLyrics(): List<Lyric> {
    if (instrumental) return listOf()

    if (lyricsfile.toString() != "null") {
        val settings = LoadSettings.builder().build()
        val raw = Load(settings).loadFromString(lyricsfile) as? Map<*, *>
            ?: throw IllegalArgumentException("Invalid YAML format")

        val linesList = raw["lines"] as? List<*> ?: emptyList<Any>()
        val lines = linesList.map { lineItem ->
            val lineMap = lineItem as? Map<*, *> ?: emptyMap<Any, Any>()

            val wordsList = lineMap["words"] as? List<*> ?: emptyList<Any>()
            val words = wordsList.map { wordItem ->
                val wordMap = wordItem as? Map<*, *> ?: emptyMap<Any, Any>()
                SyncedWord(
                    text = wordMap["text"]?.toString() ?: "",
                    startMs = wordMap["start_ms"] as? Int ?: 0,
                    endMs = wordMap["end_ms"] as? Int
                )
            }

            Lyric(
                text = listOf(lineMap["text"]?.toString() ?: ""),
                words = words,
                startMs = lineMap["start_ms"]?.toString()?.toInt() ?: 0,
                endMs = lineMap["end_ms"]?.toString()?.toInt() ?: 0
            )
        }
        return lines
    }
    else if (syncedLyrics.toString() != "null") {
        val raw = mutableListOf<Pair<Int, String>>()
        syncedLyrics?.lines()?.forEach { lyric ->
            if (lyric.isBlank())
                return@forEach
            val timeStampsRaw = getTimeStamps(lyric)[0]
            val time = mmssToMilliseconds(timeStampsRaw).toInt()
            val lyricText = lyric.substringAfter("]").trim()
            raw.add(Pair(time, lyricText))
        }

        return raw
            .groupBy { it.first }
            .map { (time, lines) -> Lyric(time, lines.map { it.second }) }
            .sortedBy { it.startMs }
    }
    else if (plainLyrics.toString() != "null") {
        Log.d("LYRICS", "Got LRCLIB plain lyrics: $plainLyrics")
        return listOf(Lyric(-1, listOf(plainLyrics.toString())))
    }
    else
        return listOf()
}

fun NeteaseLyricsResponse.toLyrics(): List<Lyric> {
    if (pureMusic == true)
        return emptyList()

    val originalMap = mutableMapOf<Int, String>()
    val translationMap = mutableMapOf<Int, String>()

    lrc?.lyric?.lines()?.forEach { line ->
        val tags = getTimeStamps(line)
        if (tags.isEmpty()) return@forEach
        val text = line.substringAfter("]").trim()
        tags.forEach { tag ->
            val time = mmssToMilliseconds(tag).toInt()
            originalMap[time] = text
        }
    }
    if (!tlyric?.lyric.isNullOrEmpty()) {
        tlyric.lyric.lines().forEach { line ->
            val tags = getTimeStamps(line)
            if (tags.isEmpty()) return@forEach
            // Group lines sharing the same timestamp
            val text = line.substringAfter("]").trim()
            tags.forEach { tag ->
                val time = mmssToMilliseconds(tag).toInt()
                translationMap[time] = text
            }
        }
    }

    // Group lines sharing the same timestamp
    return originalMap
        .map { (timestamp, origLine) ->
            val lines = buildList {
                add(origLine)
                translationMap[timestamp]?.let { add(it) }
            }
            Lyric(startMs = timestamp, text = lines)
        }
        .sortedBy { it.startMs }
}

//endregion

fun mmssToMilliseconds(mmss: String): Long {
    val parts = mmss.split(":", ".")
    if (parts.size >= 3) {
        try {
            val minutes = parts[0].toLong()
            val seconds = parts[1].toLong()
            val msRaw = parts[2]
            val ms = when (msRaw.length) {
                1 -> msRaw.toLong() * 100
                2 -> msRaw.toLong() * 10
                else -> msRaw.substring(0, 3).toLong()
            }
            return (minutes * 60 + seconds) * 1000 + ms
        } catch (e: NumberFormatException) {
            e.printStackTrace()
        }
    } else if (parts.size == 2) {
        try {
            val minutes = parts[0].toLong()
            val seconds = parts[1].toLong()
            return (minutes * 60 + seconds) * 1000
        } catch (e: NumberFormatException) {
            e.printStackTrace()
        }
    }
    return 0L
}

fun getTimeStamps(input: String): List<String> {
    val regex = Regex("\\[(.*?)]")
    val matches = regex.findAll(input)

    val result = mutableListOf<String>()
    for (match in matches) {
        result.add(match.groupValues[1])
    }

    return result
}

fun parseLrc(content: String): List<Lyric> {
    if (content.isBlank()) return emptyList()
    val raw = mutableListOf<Pair<Int, String>>()
    val lines = content.lines()
    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        val tags = getTimeStamps(trimmed)
        if (tags.isEmpty()) continue
        val text = trimmed.substringAfterLast("]").trim()
        for (tag in tags) {
            val ms = mmssToMilliseconds(tag).toInt()
            raw.add(Pair(ms, text))
        }
    }
    if (raw.isNotEmpty()) {
        return raw.groupBy { it.first }
            .map { (time, pairs) -> Lyric(time, pairs.map { it.second }.filter { it.isNotBlank() }) }
            .filter { it.text.isNotEmpty() }
            .sortedBy { it.startMs }
    }
    val plainLines = lines.map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("[") }
    if (plainLines.isNotEmpty()) {
        return listOf(Lyric(startMs = -1, text = plainLines))
    }
    return emptyList()
}

fun List<Lyric>.toLrcString(): String {
    val sb = StringBuilder()
    for (lyric in this) {
        if (lyric.startMs >= 0) {
            val totalSeconds = lyric.startMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            val millis = (lyric.startMs % 1000) / 10
            val timeTag = String.format("[%02d:%02d.%02d]", minutes, seconds, millis)
            for (line in lyric.text) {
                if (line.isNotBlank()) {
                    sb.append(timeTag).append(line).append("\n")
                }
            }
        } else {
            for (line in lyric.text) {
                if (line.isNotBlank()) {
                    sb.append(line).append("\n")
                }
            }
        }
    }
    return sb.toString()
}