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
package com.pedro.library.srt

import android.content.Context
import android.media.MediaCodec
import android.os.Build
import androidx.annotation.RequiresApi
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.library.base.DisplayBase
import com.pedro.library.util.streamclient.SrtStreamClient
import com.pedro.library.util.streamclient.StreamClientListener
import com.pedro.srt.srt.SrtClient
import java.nio.ByteBuffer

/**
 * More documentation see:
 * [DisplayBase]
 *
 * Created by pedro on 8/9/23.
 */
@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
class SrtDisplay(context: Context, useOpengl: Boolean, connectChecker: ConnectChecker): DisplayBase(context, useOpengl) {

  private val streamClientListener = object: StreamClientListener {
    override fun onRequestKeyframe() {
      requestKeyFrame()
    }
  }
  private val srtClient = SrtClient(connectChecker)
  private val streamClient = SrtStreamClient(srtClient, streamClientListener)

  override fun setVideoCodecImp(codec: VideoCodec) {
    srtClient.setVideoCodec(codec)
  }

  override fun setAudioCodecImp(codec: AudioCodec) {
    srtClient.setAudioCodec(codec)
  }

  override fun getStreamClient(): SrtStreamClient = streamClient

  override fun onAudioInfoImp(isStereo: Boolean, sampleRate: Int) {
    srtClient.setAudioInfo(sampleRate, isStereo)
  }

  override fun startStreamImp(url: String) {
    srtClient.connect(url)
  }

  override fun stopStreamImp() {
    srtClient.disconnect()
  }

  override fun getAudioDataImp(audioBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
    srtClient.sendAudio(audioBuffer, info)
  }

  override fun onVideoInfoImp(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
    srtClient.setVideoInfo(sps, pps, vps)
  }

  override fun getVideoDataImp(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
    srtClient.sendVideo(videoBuffer, info)
  }
}
