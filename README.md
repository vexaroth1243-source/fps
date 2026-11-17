# FpsOverlay Mobile (Android)

Masaüstü FpsOverlay'in Android karşılığı: kartlı izleme ekranı (halka, büyük rakam, geçmiş grafiği)
ve diğer uygulamaların üzerinde duran küçük gösterge.

## Derleme

1. Android Studio'yu aç → **Open** → bu klasörü seç. Gradle senkronu bitsin (ilk seferde indirme yapar).
2. Telefonda Geliştirici seçenekleri → USB hata ayıklama açık olsun, telefonu bağla.
3. **Run ▶**. APK için: *Build → Build APK(s)*.

Gereksinim: Android 8.0+ (API 26), Android Studio Koala veya üstü, JDK 17.

## İzinler

- **Diğer uygulamaların üzerinde göster**: gösterge için. "Göstergeyi başlat"a basınca ayar ekranı açılır.
- **Bildirim**: gösterge bir ön plan servisidir; bildirimden "Tıklamayı geçir" ve "Durdur" kullanılır.

## Ölçüm doğruluğu

| Değer | Kaynak | Root gerekir mi |
|---|---|---|
| RAM | `ActivityManager.MemoryInfo` | Hayır |
| Pil sıcaklığı / şarj | `ACTION_BATTERY_CHANGED` | Hayır |
| CPU kullanımı | `/proc/stat` | Android 8+ çoğu cihazda engelli → root |
| CPU hızı | `cpufreq/scaling_cur_freq` (çekirdek ortalaması) | Cihaza bağlı |
| CPU / GPU sıcaklığı | `thermal_zone*` (tür adına göre seçilir; en sıcak bölge) | Cihaza bağlı |
| GPU kullanımı | Adreno `kgsl`, Mali ve MediaTek yolları | Cihaza bağlı |
| FPS, kare süresi, %1 düşük | `dumpsys SurfaceFlinger --latency` kare zaman damgaları | **Evet** |

Okunamayan değer kartta **Yok** olarak görünür; uydurma değer gösterilmez.
**Katman → Sensör raporunu kaydet** hangi dosyanın okunabildiğini döker.

## Bilinen sınırlar

- Root olmadan başka bir uygulamanın FPS'i ölçülemez (Android bunu engeller).
- GPU yükü ve CPU/GPU sıcaklığı üreticiye göre değişir; bazı telefonlarda hiç okunamaz.
- Gösterge tam ekran oyunlarda genelde görünür, ancak bazı oyunlar/cihazlar kaplama pencerelerini gizleyebilir.
- Anti-cheat'li çevrimiçi oyunlarda root ve kaplama pencereleri sorun çıkarabilir. Önce çevrimdışı dene.
- Kayıtlar `Android/data/com.fpsoverlay/files/Kayitlar/` altına yazılır.

## Yapı

- `Ui.kt`, `MainActivity.kt`: izleme ve katman sekmeleri (Compose)
- `OverlayService.kt`: köşedeki gösterge, sürükleme, tıklama geçirme
- `Sampler.kt`: ölçüm döngüleri, geçmiş, CSV kaydı, rapor
- `FpsSource.kt`, `HwSource.kt`, `Sys.kt`: ölçüm kaynakları ve dosya/root yardımcıları
- `Metrics.kt`, `Settings.kt`

## APK nasıl alınır (telefona kurulabilen dosya)

Zip dosyası telefona kurulmaz; kurulabilen dosya `.apk`'dır.

**Yol 1: Android Studio (bilgisayarda)**
1. Klasörü Android Studio ile aç, senkron bitsin.
2. *Build → Build APK(s)*. Bitince sağ altta "locate" çıkar.
3. Dosya: `app/build/outputs/apk/debug/app-debug.apk`. Telefona at (kablo, Drive, Telegram).

**Yol 2: GitHub ile (Android Studio kurmadan)**
1. github.com'da ücretsiz hesap aç, yeni bir depo (repository) oluştur.
2. Bu klasörün İÇİNDEKİ her şeyi depoya yükle (`.github` klasörü dahil).
3. Depoda *Actions* sekmesi → "APK derle" → bitince sayfanın altındaki **FpsOverlay-apk** dosyasını indir, zip'ten çıkan `app-debug.apk`'yı telefona at.

**Telefonda kurulum**
1. Telefonda `app-debug.apk`'ya dokun.
2. "Bilinmeyen uygulamaları yükle" izni isterse, açan uygulama (Dosyalar/Chrome/Telegram) için izin ver.
3. Play Protect uyarısı çıkarsa "Yine de yükle". Uygulama kendi derlemen olduğu için uyarı normaldir.
4. Kurulunca uygulama çekmecesinde **FPS Overlay** simgesiyle görünür; simgeyi ana ekrana sürükle.
