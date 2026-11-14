package com.noveria.cast

import java.io.ByteArrayOutputStream
import kotlin.math.min

/** H.264 (Annex-B) karelerini yalnızca-video MPEG-TS paketlerine sarar. */
class LiveTsMuxer {

    private var ccVideo = 0
    private var ccPat = 0
    private var ccPmt = 0
    private val aud = b(0x00, 0x00, 0x00, 0x01, 0x09, 0xF0)

    private val patSection = withCrc(
        b(0x00, 0xB0, 0x0D, 0x00, 0x01, 0xC1, 0x00, 0x00, 0x00, 0x01,
            0xE0 or (PID_PMT shr 8), PID_PMT and 0xFF)
    )
    private val pmtSection = withCrc(
        b(0x02, 0xB0, 0x12, 0x00, 0x01, 0xC1, 0x00, 0x00,
            0xE0 or (PID_VIDEO shr 8), PID_VIDEO and 0xFF, 0xF0, 0x00,
            0x1B, 0xE0 or (PID_VIDEO shr 8), PID_VIDEO and 0xFF, 0xF0, 0x00)
    )

    /** Bir kodlanmış kareyi TS paketlerine çevirir (anahtar karede önce PAT/PMT eklenir). */
    fun mux(es: ByteArray, ptsUs: Long, key: Boolean, spsPps: ByteArray?): ByteArray {
        val out = ByteArrayOutputStream(es.size + es.size / 40 + 1024)
        if (key) {
            writePsi(out, PID_PAT, patSection, true)
            writePsi(out, PID_PMT, pmtSection, false)
        }

        val pts90 = 90000L + ptsUs * 9 / 100
        val pcr90 = pts90 - 9000
        val extra = if (key && spsPps != null) spsPps else ByteArray(0)
        val pes = ByteArray(14 + aud.size + extra.size + es.size)
        pes[0] = 0; pes[1] = 0; pes[2] = 1; pes[3] = 0xE0.toByte()
        pes[4] = 0; pes[5] = 0                 // uzunluk 0 = sınırsız (video için geçerli)
        pes[6] = 0x80.toByte(); pes[7] = 0x80.toByte(); pes[8] = 5
        pes[9] = (0x21 or (((pts90 shr 30) and 0x07).toInt() shl 1)).toByte()
        pes[10] = ((pts90 shr 22) and 0xFF).toByte()
        pes[11] = ((((pts90 shr 15) and 0x7F).toInt() shl 1) or 1).toByte()
        pes[12] = ((pts90 shr 7) and 0xFF).toByte()
        pes[13] = ((((pts90) and 0x7F).toInt() shl 1) or 1).toByte()
        var p = 14
        System.arraycopy(aud, 0, pes, p, aud.size); p += aud.size
        System.arraycopy(extra, 0, pes, p, extra.size); p += extra.size
        System.arraycopy(es, 0, pes, p, es.size)

        var off = 0
        var first = true
        while (off < pes.size) {
            val avail = if (first) 176 else 184
            val n = min(pes.size - off, avail)
            writeVideoPacket(out, first, pes, off, n, if (first) pcr90 else null, first && key)
            off += n
            first = false
        }
        return out.toByteArray()
    }

    private fun writeVideoPacket(
        out: ByteArrayOutputStream, pusi: Boolean, data: ByteArray, off: Int, n: Int,
        pcr: Long?, randomAccess: Boolean
    ) {
        var afLen = if (pcr != null) 7 else -1
        val availPayload = 184 - (if (afLen >= 0) afLen + 1 else 0)
        if (n < availPayload) {
            val stuff = availPayload - n
            afLen = if (afLen < 0) stuff - 1 else afLen + stuff
        }
        val pkt = ByteArray(188)
        pkt[0] = 0x47
        pkt[1] = ((if (pusi) 0x40 else 0) or (PID_VIDEO shr 8)).toByte()
        pkt[2] = (PID_VIDEO and 0xFF).toByte()
        val afc = if (afLen >= 0) 3 else 1
        pkt[3] = ((afc shl 4) or ccVideo).toByte()
        ccVideo = (ccVideo + 1) and 0x0F
        var p = 4
        if (afLen >= 0) {
            pkt[p++] = afLen.toByte()
            if (afLen >= 1) {
                var flags = 0
                if (pcr != null) flags = flags or 0x10
                if (randomAccess) flags = flags or 0x40
                pkt[p++] = flags.toByte()
                var used = 1
                if (pcr != null) {
                    pkt[p++] = ((pcr shr 25) and 0xFF).toByte()
                    pkt[p++] = ((pcr shr 17) and 0xFF).toByte()
                    pkt[p++] = ((pcr shr 9) and 0xFF).toByte()
                    pkt[p++] = ((pcr shr 1) and 0xFF).toByte()
                    pkt[p++] = ((((pcr and 1L).toInt()) shl 7) or 0x7E).toByte()
                    pkt[p++] = 0
                    used += 6
                }
                while (used < afLen) {
                    pkt[p++] = 0xFF.toByte()
                    used++
                }
            }
        }
        System.arraycopy(data, off, pkt, p, n)
        out.write(pkt)
    }

    private fun writePsi(out: ByteArrayOutputStream, pid: Int, section: ByteArray, isPat: Boolean) {
        val pkt = ByteArray(188) { 0xFF.toByte() }
        val cc = if (isPat) ccPat else ccPmt
        if (isPat) ccPat = (ccPat + 1) and 0x0F else ccPmt = (ccPmt + 1) and 0x0F
        pkt[0] = 0x47
        pkt[1] = (0x40 or (pid shr 8)).toByte()
        pkt[2] = (pid and 0xFF).toByte()
        pkt[3] = (0x10 or cc).toByte()
        pkt[4] = 0
        System.arraycopy(section, 0, pkt, 5, section.size)
        out.write(pkt)
    }

    private fun withCrc(body: ByteArray): ByteArray {
        var crc = -1
        for (byte in body) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 24)
            repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
        }
        return body + b(crc ushr 24, (crc ushr 16) and 0xFF, (crc ushr 8) and 0xFF, crc and 0xFF)
    }

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private companion object {
        const val PID_PAT = 0x0000
        const val PID_PMT = 0x1000
        const val PID_VIDEO = 0x0100
    }
}
