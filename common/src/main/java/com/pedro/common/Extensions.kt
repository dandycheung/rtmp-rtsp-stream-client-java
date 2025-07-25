/*
 * Copyright (C) 2024 pedroSG94.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pedro.common

import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.annotation.RequiresApi
import com.pedro.common.frame.MediaFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.io.UnsupportedEncodingException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation

/**
 * Created by pedro on 3/11/23.
 */

@Suppress("DEPRECATION")
fun MediaCodec.BufferInfo.isKeyframe(): Boolean {
  return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
    this.flags == MediaCodec.BUFFER_FLAG_KEY_FRAME
  } else {
    this.flags == MediaCodec.BUFFER_FLAG_SYNC_FRAME
  }
}

fun ByteBuffer.toByteArray(): ByteArray {
  return if (this.hasArray() && !isDirect) {
    this.array()
  } else {
    this.rewind()
    val byteArray = ByteArray(this.remaining())
    this.get(byteArray)
    byteArray
  }
}

fun ByteBuffer.removeInfo(info: MediaFrame.Info): ByteBuffer {
  try {
    position(info.offset)
    limit(info.size)
  } catch (ignored: Exception) { }
  return slice()
}

inline infix fun <reified T: Any> BlockingQueue<T>.trySend(item: T): Boolean {
  return try {
    this.add(item)
    true
  } catch (e: IllegalStateException) {
    false
  }
}

suspend fun onMainThread(code: () -> Unit) {
  withContext(Dispatchers.Main) {
    code()
  }
}

fun onMainThreadHandler(code: () -> Unit) {
  Handler(Looper.getMainLooper()).post(code)
}

fun ByteArray.bytesToHex(): String {
  return joinToString("") { "%02x".format(it) }
}

@JvmOverloads
fun ExecutorService.secureSubmit(timeout: Long = 5000, code: () -> Unit) {
  try {
    if (isTerminated || isShutdown) return
    submit { code() }.get(timeout, TimeUnit.MILLISECONDS)
  } catch (ignored: InterruptedException) {}
}

fun String.getMd5Hash(): String {
  val md: MessageDigest
  try {
    md = MessageDigest.getInstance("MD5")
    return md.digest(toByteArray()).bytesToHex()
  } catch (ignore: NoSuchAlgorithmException) {
  } catch (ignore: UnsupportedEncodingException) {
  }
  return ""
}

fun newSingleThreadExecutor(queue: LinkedBlockingQueue<Runnable>): ExecutorService {
  return ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, queue)
}

fun getSuspendContext(): Continuation<Unit> {
  return object : Continuation<Unit> {
    override val context = Dispatchers.IO
    override fun resumeWith(result: Result<Unit>) {}
  }
}

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
fun <T> CameraCharacteristics.secureGet(key: CameraCharacteristics.Key<T>): T? {
  return try { get(key) } catch (e: IllegalArgumentException) { null }
}

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
fun <T> CaptureRequest.Builder.secureGet(key: CaptureRequest.Key<T>): T? {
  return try { get(key) } catch (e: IllegalArgumentException) { null }
}

fun String.getIndexes(char: Char): Array<Int> {
  val indexes = mutableListOf<Int>()
  forEachIndexed { index, c -> if (c == char) indexes.add(index) }
  return indexes.toTypedArray()
}

fun Throwable.validMessage(): String {
  return (message ?: "").ifEmpty { javaClass.simpleName }
}

fun MediaCodec.BufferInfo.toMediaFrameInfo() = MediaFrame.Info(offset, size, presentationTimeUs, isKeyframe())

fun ByteBuffer.clone(): ByteBuffer = ByteBuffer.wrap(toByteArray())

fun MediaFormat.getIntegerSafe(name: String): Int? {
  return try { getInteger(name) } catch (e: Exception) { null }
}

fun MediaFormat.getLongSafe(name: String): Long? {
  return try { getLong(name) } catch (e: Exception) { null }
}

fun Int.toUInt16(): ByteArray = byteArrayOf((this ushr 8).toByte(), this.toByte())

fun Int.toUInt24(): ByteArray {
  return byteArrayOf((this ushr 16).toByte(), (this ushr 8).toByte(), this.toByte())
}

fun Int.toUInt32(): ByteArray {
  return byteArrayOf((this ushr 24).toByte(), (this ushr 16).toByte(), (this ushr 8).toByte(), this.toByte())
}

fun Int.toUInt32LittleEndian(): ByteArray = Integer.reverseBytes(this).toUInt32()

fun ByteArray.toUInt16(): Int {
  return this[0].toInt() and 0xff shl 8 or (this[1].toInt() and 0xff)
}

fun ByteArray.toUInt24(): Int {
  return this[0].toInt() and 0xff shl 16 or (this[1].toInt() and 0xff shl 8) or (this[2].toInt() and 0xff)
}

fun ByteArray.toUInt32(): Int {
  return this[0].toInt() and 0xff shl 24 or (this[1].toInt() and 0xff shl 16) or (this[2].toInt() and 0xff shl 8) or (this[3].toInt() and 0xff)
}

fun ByteArray.toUInt32LittleEndian(): Int {
  return Integer.reverseBytes(toUInt32())
}

fun InputStream.readUntil(byteArray: ByteArray) {
  var bytesRead = 0
  while (bytesRead < byteArray.size) {
    val result = read(byteArray, bytesRead, byteArray.size - bytesRead)
    if (result != -1) bytesRead += result
  }
}

fun InputStream.readUInt32(): Int {
  val data = ByteArray(4)
  read(data)
  return data.toUInt32()
}

fun InputStream.readUInt24(): Int {
  val data = ByteArray(3)
  read(data)
  return data.toUInt24()
}

fun InputStream.readUInt16(): Int {
  val data = ByteArray(2)
  read(data)
  return data.toUInt16()
}

fun InputStream.readUInt32LittleEndian(): Int {
  return Integer.reverseBytes(readUInt32())
}

fun OutputStream.writeUInt32(value: Int) {
  write(value.toUInt32())
}

fun OutputStream.writeUInt24(value: Int) {
  write(value.toUInt24())
}

fun OutputStream.writeUInt16(value: Int) {
  write(value.toUInt16())
}

fun OutputStream.writeUInt32LittleEndian(value: Int) {
  write(value.toUInt32LittleEndian())
}

fun Long.compare(l: Long): Int {
  return if (this < l) -1 else if (this > l) 1 else 0
}

fun SurfaceTexture.tryClear() {
  val surface = Surface(this)
  try {
    val canvas = surface.lockCanvas(null)
    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
    surface.unlockCanvasAndPost(canvas)
  } catch (_: Exception) {} finally {
    surface.release()
  }
}