package com.noveria.cast

import kotlinx.coroutines.flow.MutableStateFlow

/** Servis ile arayüz arasındaki ortak durum. */
object CastState {
    /** Şu anda yansıtılan cihazın id'si (host:port), yoksa null. */
    val castingDeviceId = MutableStateFlow<String?>(null)

    /** Kullanıcıya gösterilecek son hata mesajı. */
    val lastError = MutableStateFlow<String?>(null)
}
