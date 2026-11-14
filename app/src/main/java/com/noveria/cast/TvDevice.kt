package com.noveria.cast

/** Ağda bulunan / kaydedilen TV alıcısı. */
data class TvDevice(
    val name: String,
    val host: String,
    val port: Int
) {
    val id: String get() = "$host:$port"
}
