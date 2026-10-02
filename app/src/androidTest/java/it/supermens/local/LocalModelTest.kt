// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.
package it.supermens.local

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

class LocalModelTest {
    private lateinit var context: Context
    private lateinit var root: File
    @Before fun setup() {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val id=UUID.randomUUID().toString()
        root=File(base.cacheDir,"model-test-$id").apply {mkdirs()}
        context=object:ContextWrapper(base) {
            override fun getExternalFilesDir(type:String?)=File(root,"external").apply {mkdirs()}
            override fun getSharedPreferences(name:String,mode:Int)=base.getSharedPreferences("$id-$name",mode)
        }
    }
    @After fun cleanup() {root.deleteRecursively();context.getSharedPreferences("local_model",Context.MODE_PRIVATE).edit().clear().commit()}
    @Test fun matchingSizeAloneCannotDeclareModelReady() {
        val target=LocalModel.file(context)
        target.parentFile!!.mkdirs()
        RandomAccessFile(target,"rw").use {it.setLength(LocalModel.expectedBytes)}
        assertEquals(LocalModel.expectedBytes,target.length())
        assertFalse("Unverified or corrupted weights must not enter the AI queue",LocalModel.ready(context))
    }
    @Test fun invalidImportPreservesExistingModelFile() {
        val target=LocalModel.file(context)
        target.parentFile!!.mkdirs();target.writeText("Existing model bytes")
        val invalid=File(root,"invalid.litertlm").apply {writeText("Not a model")}
        try {LocalModel.import(context,Uri.fromFile(invalid));fail("Invalid model was accepted")}
        catch (_:IllegalArgumentException) {}
        assertEquals("Existing model bytes",target.readText())
        assertFalse(LocalModel.ready(context))
        assertFalse(target.parentFile!!.listFiles()!!.any {it.name.startsWith("model-import-")})
    }
}
