package app.aether.core.lyrics

import java.util.Locale

/**
 * Lyrics written out as LRC, to travel inside a kept song's file (its `LYRICS` tag), as other players
 * read them: a stamp for every line, and a stamp before every word where the words were timed
 * (enhanced LRC), so the song lights word by word again when it's read back. Plain words stay plain.
 */
object LrcWriter {
    fun write(lyrics: Lyrics): String? = when (lyrics) {
        is Lyrics.Synced -> buildString {
            for (line in lyrics.lines) {
                append('[').append(stamp(line.startMs)).append(']')
                if (line.words.isNotEmpty() && line.words.size == WordTiming.words(line).size) {
                    line.words.forEachIndexed { i, w ->
                        if (i > 0) append(' ')
                        append('<').append(stamp(w.startMs)).append('>').append(w.text)
                    }
                } else append(line.text)
                append('\n')
            }
        }.takeIf { it.isNotBlank() }
        is Lyrics.Plain -> lyrics.lines.joinToString("\n").takeIf { it.isNotBlank() }
        Lyrics.Instrumental -> null
    }

    /**
     * `mm:ss.xx`, always in ASCII digits: the phone's own locale may write numbers in its own digits
     * (Arabic, Persian, Marathi…), which no LRC reader, [LrcParser] included, takes as a time.
     */
    internal fun stamp(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        return String.format(Locale.ROOT, "%02d:%02d.%02d", t / 60_000, (t / 1000) % 60, (t % 1000) / 10)
    }
}
