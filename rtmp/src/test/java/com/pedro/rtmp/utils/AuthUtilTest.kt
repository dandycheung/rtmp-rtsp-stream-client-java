/*
 * Copyright (C) 2021 pedroSG94.
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

package com.pedro.rtmp.utils

import com.pedro.rtmp.flv.video.VideoSpecificConfigHEVC
import com.pedro.rtmp.flv.video.asd.HEVCDecoderConfigurationRecord
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class AuthUtilTest {

    @Test
    fun `when list of numbers converted to hex, then correct concatenated hex returned`() {
        val numbersToHex = mapOf(
            255 to "ff", 128 to "80", 8 to "08", 1 to "01", 0 to "00", -255 to "01", -1 to "ff"
        )
        val testBytes = numbersToHex.keys.map { it.toByte()}.toByteArray()
        val expectedHex = numbersToHex.values.reduce { acc, s -> acc + s }
        assertEquals(expectedHex, AuthUtil.bytesToHex(testBytes))
    }

    @Test
    fun `convert string to MD5 and that MD5 to base64 string`() {
        assertEquals("XrY7u+Ae7tCTyyK7j1rNww==", AuthUtil.stringToMd5Base64("hello world"))
    }

    @Test
    fun asd() {
        val vps = listOf<Byte>(64, 1, 12, 1, -1, -1, 1, 96, 0, 0, 3, 0, 0, 3, 0, 0, 3, 0, 0, 3, 0, -103, 44, 9).toByteArray()
        val sps = listOf<Byte>(66, 1, 1, 1, 96, 0, 0, 3, 0, 0, 3, 0, 0, 3, 0, 0, 3, 0, -103, -96, 15, 8, 2, -127, 104, -76, -82, -55, 46, -26, -96, -64, -64, -64, 16).toByteArray()
        val pps = listOf<Byte>(68, 1, -64, 102, 124, 12, -58, 64).toByteArray()
        val config2 = VideoSpecificConfigHEVC(sps, pps, vps)
        val data = ByteBuffer.allocate(config2.size)
        val data2 = ByteArray(config2.size)
        val config = HEVCDecoderConfigurationRecord.fromParameterSets(ByteBuffer.wrap(sps), ByteBuffer.wrap(pps), ByteBuffer.wrap(vps))
        config.write(data)
        config2.write(data2, 0)
        assertArrayEquals(data.array(), data2)
    }
}