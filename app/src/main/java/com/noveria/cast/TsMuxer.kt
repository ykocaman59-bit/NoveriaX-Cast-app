package com.noveria.cast

import java.io.ByteArrayOutputStream

/**
 * Yalnızca H.264 video taşıyan minimal MPEG-TS muxer (HLS segmentleri için).
 * Girdi: Annex-B (00 00 00 01 ...) formatında H.264 erişim birimleri.
 */
class TsMuxer {

    private var ccPat = 0
    private var ccPmt = 0
    private var ccVid = 0

    /** PAT + PMT (her segmentin başına konur). */
    fun patPmt(): ByteArray {
        val pat = byteArrayOf(
            0x00, // table_id
            0xB0.toByte(), 0x0D, // section_syntax=1, length=13
            0x00, 0x01, // transport_stream_id
            0xC1.toByte(), 0x00, 0x00, // version/current, section, last section
            0x00, 0x01, // program_number
            (0xE0 or (PMT_PID shr 8)).toByte(), (PMT_PID and 0xFF).toByte()
        )
        val pmt = byteArrayOf(
            0x02, // table_id
            0xB0.toByte(), 0x12, // length=18
            0x00, 0x01, // program_number
            0xC1.toByte(), 0x00, 0x00,
            (0xE0 or (VIDEO_PID shr 8)).toByte(), (VIDEO_PID and 0xFF).toByte(), // PCR PID
            0xF0.toByte(), 0x00, // program_info_length
            0x1B, // H.264
            (0xE0 or (VIDEO_PID shr 8)).toByte(), (VIDEO_PID and 0xFF).toByte(),
            0xF0.toByte(), 0x00 // ES_info_length
        )
        val out = ByteArrayOutputStream()
        out.write(psiPacket(0, ccPat++ and 0x0F, withCrc(pat)))
        out.write(psiPacket(PMT_PID, ccPmt++ and 0x0F, withCrc(pmt)))
        return out.toByteArray()
    }

    /** Bir video erişim birimini PES + TS paketlerine çevirir. */
    fun video(annexB: ByteArray, ptsUs: Long, keyframe: Boolean): ByteArray {
        val pts = BASE_TS + ptsUs * 9 / 100 // 90 kHz
        val pes = ByteArrayOutputStream(annexB.size + 32)
        pes.write(byteArrayOf(0, 0, 1, 0xE0.toByte(), 0, 0, 0x80.toByte(), 0x80.toByte(), 0x05))
        pes.write(ptsBytes(pts))
        pes.write(AUD)
        pes.write(annexB)
        val data = pes.toByteArray()

        val out = ByteArrayOutputStream(data.size + data.size / 100 * 4 + 400)
        var off = 0
        var first = true
        val pcr = pts - PCR_LEAD
        while (off < data.size) {
            val pkt = ByteArray(188) { 0xFF.toByte() }
            pkt[0] = 0x47
            pkt[1] = ((if (first) 0x40 else 0x00) or (VIDEO_PID shr 8)).toByte()
            pkt[2] = (VIDEO_PID and 0xFF).toByte()
            val remaining = data.size - off
            val base = if (first) 8 else 0
            val cap = 184 - base
            val take: Int
            val afTotal: Int
            if (remaining >= cap) {
                take = cap
                afTotal = base
            } else {
                take = remaining
                afTotal = 184 - remaining
            }
            pkt[3] = ((if (afTotal > 0) 0x30 else 0x10) or (ccVid++ and 0x0F)).toByte()
            if (afTotal > 0) {
                pkt[4] = (afTotal - 1).toByte()
                if (afTotal > 1) {
                    var flags = 0
                    if (first) flags = flags or 0x10 // PCR var
                    if (first && keyframe) flags = flags or 0x40 // random access
                    pkt[5] = flags.toByte()
                    if (first) {
                        pkt[6] = ((pcr shr 25) and 0xFF).toByte()
                        pkt[7] = ((pcr shr 17) and 0xFF).toByte()
                        pkt[8] = ((pcr shr 9) and 0xFF).toByte()
                        pkt[9] = ((pcr shr 1) and 0xFF).toByte()
                        pkt[10] = ((((pcr and 1L) shl 7).toInt()) or 0x7E).toByte()
                        pkt[11] = 0
                    }
                }
            }
            System.arraycopy(data, off, pkt, 4 + afTotal, take)
            off += take
            first = false
            out.write(pkt)
        }
        return out.toByteArray()
    }

    private fun ptsBytes(pts: Long) = byteArrayOf(
        (0x20 or (((pts shr 30) and 0x07).toInt() shl 1) or 1).toByte(),
        ((pts shr 22) and 0xFF).toByte(),
        ((((pts shr 15) and 0x7F).toInt() shl 1) or 1).toByte(),
        ((pts shr 7) and 0xFF).toByte(),
        ((((pts and 0x7F).toInt()) shl 1) or 1).toByte()
    )

    private fun psiPacket(pid: Int, cc: Int, section: ByteArray): ByteArray {
        val pkt = ByteArray(188) { 0xFF.toByte() }
        pkt[0] = 0x47
        pkt[1] = (0x40 or (pid shr 8)).toByte()
        pkt[2] = (pid and 0xFF).toByte()
        pkt[3] = (0x10 or cc).toByte()
        pkt[4] = 0x00 // pointer_field
        System.arraycopy(section, 0, pkt, 5, section.size)
        return pkt
    }

    private fun withCrc(section: ByteArray): ByteArray {
        val crc = crc32Mpeg(section)
        return section + byteArrayOf(
            (crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte()
        )
    }

    private fun crc32Mpeg(data: ByteArray): Int {
        var crc = -1 // 0xFFFFFFFF
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 24)
            repeat(8) {
                crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1
            }
        }
        return crc
    }

    private companion object {
        const val PMT_PID = 0x1000
        const val VIDEO_PID = 0x100
        const val BASE_TS = 180_000L // 2 sn (negatif zaman damgasını önler)
        const val PCR_LEAD = 9_000L // 0,1 sn
        val AUD = byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte())
    }
}
