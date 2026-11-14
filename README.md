# NoveriaX Cast app (tek APK)

Telefona kurulan **tek bir APK**. TV'ye hiçbir şey kurulmaz.
Telefon ve TV aynı Wi-Fi ağındayken TV'leri bulur, listeye ekler ve telefon ekranını TV'ye yansıtır.

## İki mod
1. **Video / Foto Gönder** (filmlerdeki Cast butonu gibi): Telefondaki video/foto/ses TV'ye gönderilir, TV dosyayı telefondan
   kendisi çekip oynatır. Gecikme, kasma, donma yok; TV'nin desteklediği en yüksek kalitede (Full HD / 4K) oynar.
   TV genelde MP4 (H.264/H.265) ve JPG/PNG destekler; MKV/AVI gibi biçimler TV'ye göre oynamayabilir.
2. **Yansıt** (tüm ekran): Telefonun ekranı Full HD'ye kadar canlı yansıtılır. TV'ye uygulama kurulmadığı için
   yaklaşık 3-8 sn gecikme olur (Cast alıcısının sınırı).

## Nasıl çalışır?
- **Cihaz Tara**: Aynı ağdaki Google Cast (Chromecast yerleşik) cihazları mDNS ile bulunur (`_googlecast._tcp`).
  Android TV / Google TV cihazlarında bu özellik yerleşik gelir.
- **Ekle ve Yansıt**: Cihaz "Cihazlar" listesine kalıcı kaydedilir (uygulama kapanıp açılsa da kalır) ve yansıtma başlar.
- **Yansıt**: Telefon ekranı yakalanır (MediaProjection), H.264 olarak kodlanır, telefonda küçük bir HLS sunucusuyla
  yayınlanır; TV'nin yerleşik Cast alıcısına bu adres oynattırılır.
- **Yansıtmadan Çık**: TV'deki alıcı kapatılır, TV kendi ekranına döner.
- **Cihazı Sil**: Kayıtlı cihazı listeden kaldırır.
- **Çevrimdışı uyarısı**: Telefonun Wi-Fi'ı yoksa `Telefon - Çevrimdışı`, TV ağda cevap vermiyorsa `[TV Adı] - Çevrimdışı`.

## Derleme
1. Klasörü GitHub'a yükleyin; `.github/workflows/android.yml` her push'ta APK üretir (Actions → artifact `noveriax-cast-apk`).
2. Ya da Android Studio ile açıp çalıştırın (JDK 17).

## Bilinen sınırlar
- Ses aktarılmaz (yalnızca görüntü). DRM/`FLAG_SECURE` içerikler (Netflix vb.) siyah görünür.
- HLS yöntemi nedeniyle gecikme yaklaşık 3-8 saniyedir (video/sunum için uygun, oyun için değil).
- TV'nin IP'si değişirse kayıtlı cihaz çevrimdışı görünür; çözüm: cihazı silip yeniden taratın veya yönlendiricide TV'ye sabit IP verin.
- Google Cast desteklemeyen TV'lerde (Chromecast yerleşik olmayan eski/Android olmayan modeller) bulunamaz.
- Gradle wrapper JAR'ı pakete dahil değil; GitHub Actions Gradle 8.5'i kendisi kurar.
