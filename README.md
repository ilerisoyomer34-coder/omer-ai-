# Drift Kumanda

Bluetooth üzerinden robot sürmek için araba tarzı kumanda: manuel/otomatik vites, gaz ve fren pedalı, el freniyle drift, nitro, simit / telefon eğimi / joystick direksiyon.

Bu dal (`drift-kumanda`) deponun ana kodundan bağımsızdır. `main` dalına dokunmaz.

## Desteklenen robotlar

| Robot | Bağlantı | Not |
|---|---|---|
| Makeblock mBot2 (CyberPi) | BLE | Fabrika yazılımı yeterli, kod yüklemek gerekmez |
| Makeblock mBot (mCore) | BLE | Fabrika yazılımı |
| LEGO BOOST, City Hub, Technic Hub, Technic Move Hub | BLE (LEGO Wireless Protocol 3) | Tank ya da direksiyon motorlu araba düzeni |
| LEGO SPIKE Prime ve Essential | BLE (SPIKE 3 / hub OS 3) | Hub'a küçük bir köprü programı yüklenir (varsayılan 19. yuva) |
| HC-05 / HC-06 Arduino araba | Klasik Bluetooth (sadece Android uygulaması) | "Arduino Bluetooth RC Car" harf komutları: F B L R G I H J S, hız 0–9 ve q |
| HM-10 Arduino araba | BLE | Aynı harf komutları |

## Kurulum

- **Android:** [Son sürümden](https://github.com/ilerisoyomer34-coder/omer-ai-/releases/latest) `DriftKumanda.apk` dosyasını indirip kur. Cihazları uygulamanın içinde tarar.
- **iPhone / iPad:** App Store'dan ücretsiz **Bluefy** tarayıcısını kur, şu adresi aç: https://raw.githack.com/ilerisoyomer34-coder/omer-ai-/drift-kumanda/web/index.html
- **Bilgisayar:** Aynı adresi Chrome ya da Edge ile aç.

## Klasörler

- `web/index.html` – kumandanın tamamı (tek dosya). Hem web sürümü hem uygulamanın arayüzü.
- `android/` – Android uygulaması. Arayüzü WebView'da açar, Bluetooth taramasını ve bağlantıyı Java tarafında yapar (`MainActivity.java`).
- `.github/workflows/drift-kumanda.yml` – bu dala her gönderimde APK'yı derler, sürüm sayfasına koyar, derleme kaydını `drift-kumanda-build` dalına yazar.

İmza anahtarı (`android/app/drift-kumanda.keystore`) sadece kişisel kullanım içindir. Play Store'a yüklemek için gizli tutulan yeni bir anahtar gerekir.
