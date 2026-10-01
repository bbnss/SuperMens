package it.supermens.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YoutubeCaptionsTest {
    @Test fun recognizesSharedYoutubeLinks() {
        val id="dQw4w9WgXcQ"
        assertEquals(id,YoutubeCaptions.videoId("https://www.youtube.com/watch?v=$id&t=3"))
        assertEquals(id,YoutubeCaptions.videoId("https://youtu.be/$id?si=abc"))
        assertEquals(id,YoutubeCaptions.videoId("https://m.youtube.com/shorts/$id"))
        assertEquals(id,YoutubeCaptions.videoId("https://www.youtube.com/live/$id"))
    }

    @Test fun rejectsNonYoutubeHostAndMalformedId() {
        assertNull(YoutubeCaptions.videoId("https://youtube.com.example.com/watch?v=dQw4w9WgXcQ"))
        assertNull(YoutubeCaptions.videoId("https://www.youtube.com/watch?v=invalid"))
    }
}
