package com.fedit.shortkestatus

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Splits an MP4 into parts no longer than maxSec WITHOUT re-encoding
 * (fast, no quality loss). Cuts are placed on video keyframes so every
 * part starts with a clean picture.
 */
object VideoSplitter {

    fun durationUs(file: File): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000L
        } finally {
            r.release()
        }
    }

    fun split(input: File, outDir: File, maxSec: Int): List<File> {
        val total = durationUs(input)
        val limit = maxSec * 1_000_000L
        if (total <= limit) return listOf(input)

        val keys = keyframeTimes(input)
        val cuts = mutableListOf(0L)
        while (total - cuts.last() > limit) {
            val start = cuts.last()
            val next = keys.filter { it > start + 1_000_000L && it <= start + limit }.maxOrNull()
                ?: (start + limit) // no keyframe in range: hard cut
            cuts.add(next)
        }
        cuts.add(Long.MAX_VALUE)

        val parts = mutableListOf<File>()
        for (i in 0 until cuts.size - 1) {
            val out = File(outDir, "${input.nameWithoutExtension}_part${i + 1}.mp4")
            copyRange(input, out, cuts[i], cuts[i + 1])
            parts.add(out)
        }
        return parts
    }

    private fun keyframeTimes(input: File): List<Long> {
        val ex = MediaExtractor()
        val out = mutableListOf<Long>()
        try {
            ex.setDataSource(input.absolutePath)
            val v = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return out
            ex.selectTrack(v)
            while (ex.sampleTrackIndex >= 0) {
                if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) out.add(ex.sampleTime)
                ex.advance()
            }
        } finally {
            ex.release()
        }
        return out
    }

    private fun copyRange(input: File, out: File, startUs: Long, endUs: Long) {
        val ex = MediaExtractor()
        ex.setDataSource(input.absolutePath)
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val map = HashMap<Int, Int>()
        var maxSize = 2 * 1024 * 1024
        for (t in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(t)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
            ex.selectTrack(t)
            map[t] = muxer.addTrack(f)
            if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                maxSize = maxOf(maxSize, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
            }
            if (mime.startsWith("video/") && f.containsKey("rotation-degrees")) {
                muxer.setOrientationHint(f.getInteger("rotation-degrees"))
            }
        }
        val buf = ByteBuffer.allocate(maxSize)
        val info = MediaCodec.BufferInfo()
        val done = HashSet<Int>()
        ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        muxer.start()
        try {
            while (true) {
                val t = ex.sampleTrackIndex
                if (t < 0 || done.size == map.size) break
                val time = ex.sampleTime
                if (t in done || time < startUs) { ex.advance(); continue }
                if (time >= endUs) { done.add(t); ex.advance(); continue }
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = time - startUs
                info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(map.getValue(t), buf, info)
                ex.advance()
            }
        } finally {
            muxer.stop()
            muxer.release()
            ex.release()
        }
    }
}
