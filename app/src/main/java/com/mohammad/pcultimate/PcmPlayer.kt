package com.mohammad.pcultimate

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Plays the PC's sound at home: raw 16-bit 48 kHz stereo PCM from the stream WebSocket.
 * Keeps at most ~150 ms queued, so the sound stays close to the picture.
 */
class PcmPlayer {
    private val queue = ArrayBlockingQueue<ByteArray>(6)
    @Volatile private var running = true
    private val track: AudioTrack
    private val thread: Thread

    init {
        val rate = 48000
        val fmt = AudioFormat.Builder()
            .setSampleRate(rate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(maxOf(min * 2, 3840 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        thread = Thread({
            while (running) {
                val b = try { queue.poll(200, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { null } ?: continue
                try { track.write(b, 0, b.size) } catch (_: Exception) {}
            }
        }, "pcm-player").apply { start() }
    }

    /** data[offset..] is PCM */
    fun write(data: ByteArray, offset: Int) {
        if (!running) return
        val pcm = data.copyOfRange(offset, data.size)
        if (!queue.offer(pcm)) { queue.poll(); queue.offer(pcm) }
    }

    fun release() {
        running = false
        thread.interrupt()
        queue.clear()
        try { track.pause(); track.flush(); track.release() } catch (_: Exception) {}
    }
}
