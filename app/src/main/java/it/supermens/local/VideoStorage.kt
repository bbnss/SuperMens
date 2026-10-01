package it.supermens.local

import android.content.Context
import android.graphics.Bitmap
import android.media.*
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile

object VideoStorage {
    fun stage(context:Context,uri:Uri):File {
        val file=File.createTempFile("video-source-",".input",File(context.filesDir,"pending").apply {mkdirs()})
        try { context.contentResolver.openInputStream(uri)?.use { input->file.outputStream().use {input.copyTo(it)} } ?: error(context.uiString(R.string.file_open_failed));return file }
        catch(e:Exception) {file.delete();throw e}
    }
    fun prepare(context:Context,store:BrainStore,item:BrainItem) {
        // Legacy video attachments remain untouched.
        if(item.attachment.isNotBlank()) return
        if(item.pendingMedia.endsWith(".m4a") && File(item.pendingMedia).isFile) return
        var source=File(item.pendingMedia)
        if(!source.isFile) {
            require(item.documentUri.isNotBlank()) {context.uiString(R.string.video_relink_hint)}
            source=stage(context,Uri.parse(item.documentUri));store.update(item.id,pendingMedia=source.absolutePath)
        }
        val retriever=MediaMetadataRetriever()
        var hasAudio=false
        try {
            retriever.setDataSource(source.absolutePath)
            hasAudio=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)=="yes"
            val frame=retriever.getScaledFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,720,720)
            if(frame!=null) try {
                val thumb=File(File(context.filesDir,"attachments").apply{mkdirs()},"video-${item.id}.webp")
                thumb.outputStream().use {frame.compress(Bitmap.CompressFormat.WEBP_LOSSY,75,it)}
                store.update(item.id,thumbnail=thumb.absolutePath)
            } finally {frame.recycle()}
        } finally {retriever.release()}
        if(!hasAudio) {source.delete();store.update(item.id,pendingMedia="",sourceQuality="silent_video");return}
        val audio=File(source.parentFile,"${item.id}.m4a")
        try {
            compressAudio(context,source,audio)
            store.update(item.id,pendingMedia=audio.absolutePath)
            source.delete()
        } catch(e:Exception) {audio.delete();throw e}
    }
    /** Encode mono 16 kHz PCM to AAC; discard the one-second overlap of subsequent clips. */
    private fun compressAudio(context:Context,source:File,target:File) {
        val clips=LocalAudio.toWavClips(context,source)
        var encoder:MediaCodec?=null
        var muxer:MediaMuxer?=null
        var started=false
        try {
            encoder=MediaCodec.createEncoderByType("audio/mp4a-latm")
            val format=MediaFormat.createAudioFormat("audio/mp4a-latm",16000,1).apply {setInteger(MediaFormat.KEY_BIT_RATE,48000);setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC)}
            encoder.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);encoder.start()
            muxer=MediaMuxer(target.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var track=-1;var clipIndex=0;var input:RandomAccessFile?=null;var samples=0L;var eos=false;var finished=false
            val info=MediaCodec.BufferInfo()
            try {
                while(!finished) {
                    if(!eos) {
                        val index=encoder.dequeueInputBuffer(10000)
                        if(index>=0) {
                            val buffer=encoder.getInputBuffer(index)!!;buffer.clear()
                            var count=-1
                            while(count<0 && clipIndex<clips.size) {
                                if(input==null) input=RandomAccessFile(clips[clipIndex].file,"r").apply {seek(44L+if(clipIndex>0) 32000 else 0)}
                                val bytes=ByteArray(buffer.remaining().coerceAtMost(8192));count=input!!.read(bytes)
                                if(count>0) buffer.put(bytes,0,count)
                                else {input!!.close();input=null;clipIndex++}
                            }
                            val timestamp=samples*1_000_000/16000
                            if(count<0) {encoder.queueInputBuffer(index,0,0,timestamp,MediaCodec.BUFFER_FLAG_END_OF_STREAM);eos=true}
                            else {encoder.queueInputBuffer(index,0,count,timestamp,0);samples+=count/2}
                        }
                    }
                    when(val index=encoder.dequeueOutputBuffer(info,10000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {track=muxer.addTrack(encoder.outputFormat);muxer.start();started=true}
                        else -> if(index>=0) {
                            val buffer=encoder.getOutputBuffer(index)!!
                            if(info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0) {buffer.position(info.offset);buffer.limit(info.offset+info.size);muxer.writeSampleData(track,buffer,info)}
                            finished=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                            encoder.releaseOutputBuffer(index,false)
                        }
                    }
                }
            } finally {input?.close()}
        } finally {
            runCatching {encoder?.stop()};encoder?.release()
            if(started) runCatching {muxer?.stop()};muxer?.release()
            clips.forEach {it.file.delete()}
        }
    }
}
