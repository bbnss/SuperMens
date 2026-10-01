package it.supermens.local

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File
import java.util.Locale

object LocalAi {
    private val lock = Any()
    private var engine: Engine? = null
    private var path: String = ""
    private var vision = false
    val loaded = kotlinx.coroutines.flow.MutableStateFlow(false)
    fun modelName(context: Context) = "Gemma 4 E2B · " + if(AppLanguage.code(context)=="it") "locale" else "local"
    fun unload() = synchronized(lock) { engine?.close(); engine = null; path = ""; vision = false; loaded.value=false }
    private fun engine(context: Context): Engine {
        require(LocalModel.ready(context)) { context.uiString(R.string.install_model_hint) }
        val modelPath = LocalModel.file(context).absolutePath
        engine?.takeIf { path == modelPath }?.let { return it }
        unload()
        val configs = listOf(
            EngineConfig(modelPath = modelPath, backend = Backend.GPU(), visionBackend = Backend.GPU(), audioBackend = Backend.CPU(), maxNumTokens = 4096, cacheDir = context.cacheDir.absolutePath),
            EngineConfig(modelPath = modelPath, backend = Backend.CPU(), audioBackend = Backend.CPU(), maxNumTokens = 4096, cacheDir = context.cacheDir.absolutePath)
        )
        var lastError: Throwable? = null
        configs.forEachIndexed { index, config ->
            val candidate = Engine(config)
            try {
                candidate.initialize()
                engine = candidate; path = modelPath; vision = index == 0; loaded.value=true
                return candidate
            } catch (error: Throwable) {
                candidate.close()
                lastError = error
            }
        }
        throw IllegalStateException(context.uiString(R.string.gemma_start_failed,lastError?.message.orEmpty()), lastError)
    }
    fun generate(context: Context, prompt: String): String = synchronized(lock) {
        engine(context).createConversation().use { conversation ->
            conversation.sendMessage(prompt, mapOf("enable_thinking" to false)).toString().trim().ifBlank { error(context.uiString(R.string.gemma_no_text)) }
        }
    }
    private fun languageName(tag: String): String = Locale.forLanguageTag(tag).getDisplayLanguage(Locale.forLanguageTag(tag))
    fun summarize(context: Context, body: String, language: String, check:()->Unit = {}, read:(String)->String? = {null}, write:(String,String)->Unit = {_,_->}): String {
        fun cached(key:String,prompt:String):String { check();val k=body.hashCode().toString()+":"+language+":"+key;return read(k) ?: generate(context,prompt).also {write(k,it)} }
        require(body.isNotBlank()) { context.uiString(R.string.no_summary_text) }
        val chunks = body.chunked(8000)
        var partial = chunks.mapIndexed { index, chunk ->
            cached("chunk-$index", "Riassumi fedelmente in ${languageName(language)} ($language) il blocco ${index+1}/${chunks.size} in massimo 120 parole. Conserva nomi, numeri e fatti principali. Non inventare. Rispondi solo con il riassunto.\n\n$chunk").take(1800)
        }
        var level=0
        while (partial.size > 1) {
            level++
            partial = partial.chunked(6).mapIndexed { index,group ->
                cached("reduce-$level-$index", "Unisci questi riassunti in ${languageName(language)} in massimo 180 parole. Conserva i fatti e non inventare.\n\n${group.joinToString("\n\n")}").take(2200)
            }
        }
        return cached("final", "Scrivi un riassunto finale di 4-6 frasi in ${languageName(language)} ($language). Conserva i fatti e non inventare.\n\n${partial.single()}")
    }
    fun answer(context: Context, item: BrainItem, segments: List<Segment>, question: String, language: String, onProgress:(String)->Unit = {}, check:()->Unit = {}): Pair<String, String> =
        PostQuestions.answer(item.body,segments,question,languageName(language),{ check();generate(context,it) },onProgress)
    fun describeImage(context: Context, file: File, language: String): String = synchronized(lock) {
        val runtime = engine(context)
        require(vision) { context.uiString(R.string.vision_cpu_unavailable) }
        runtime.createConversation().use { conversation ->
            conversation.sendMessage(Contents.of(Content.ImageBytes(ImageStorage.inferenceBytes(file,AppLanguage.code(context))), Content.Text("Descrivi fedelmente questa immagine in ${languageName(language)}. Riporta anche il testo visibile, se presente.")), mapOf("enable_thinking" to false)).toString().trim()
        }
    }
    fun transcribe(context: Context, file: File, existing:Map<Int,String> = emptyMap(), onClip:(Int,Int,Long,String)->Unit = { _,_,_,_-> },shouldContinue:()->Boolean = { true }): String = synchronized(lock) {
        val runtime = engine(context)
        val clips = LocalAudio.toWavClips(context, file)
        try {
            clips.mapIndexed { index, clip ->
                if(!shouldContinue()) throw InterruptedException(context.uiString(R.string.transcription_interrupted))
                existing[index] ?: runtime.createConversation().use { conversation ->
                    val prompt = "Trascrivi fedelmente questo segmento audio nella lingua originale. Riporta solo le parole pronunciate, senza commenti. Segmento ${index+1}/${clips.size}."
                    conversation.sendMessage(Contents.of(Content.AudioFile(clip.file.absolutePath), Content.Text(prompt)), mapOf("enable_thinking" to false)).toString().trim().also { onClip(index,clips.size,clip.startMs,it) }
                }
            }.fold("") { full,part -> TranscriptMerge.join(full,part) }.trim().ifBlank { error(context.uiString(R.string.no_speech)) }
        } finally { clips.forEach { it.file.delete() } }
    }
}
