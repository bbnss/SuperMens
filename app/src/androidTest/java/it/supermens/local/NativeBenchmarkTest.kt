package it.supermens.local

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Explicit opt-in: real native inference with installed weights, never a simulated quality pass. */
class NativeBenchmarkTest {
    @Test fun benchmarkSyntheticTextWithRealModel() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("nativeBenchmark")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Install and verify weights before this hardware test",LocalModel.ready(context))
        val samples=JSONArray()
        ProcessingGuard.exclusive {
            LocalAi.unload()
            repeat(3) {index ->
                val source="On Monday the library acquired 42 books. Twelve books are about botany. The reading room opens at 09:00 and closes at 18:00. It is closed on Sunday. "
                LocalAi.generate(context,"Summarize the following data in three sentences. Preserve numbers, do not invent facts.\n\n"+source.repeat(5))
                samples.put(JSONObject(ModelDiagnostics.snapshot(context)).put("run",index+1).put("cold",index==0))
            }
            LocalAi.unload()
        }
        val file=File(context.getExternalFilesDir(null) ?: context.filesDir,"benchmark-text.json")
        file.writeText(JSONObject().put("fixture","synthetic-library-v1").put("samples",samples).toString(2))
    }
}
