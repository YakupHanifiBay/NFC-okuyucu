# NFC Kimlik Okuyucu (Telefon → Bilgisayar)

Android telefon üzerinden T.C. Kimlik Kartı'nı NFC ile okuyan (ICAO 9303 / JMRTD BAC, `DG1` + `DG2`) ve **doğum tarihi**, **son geçerlilik tarihi** ile **biyometrik kimlik fotoğrafını** aynı yerel ağdaki (Wi‑Fi / Hotspot) bilgisayara anlık olarak aktaran uygulama.

> **Gizlilik ve Sıfır Kayıt (KVKK Uyumu):**
> - Kamera ile çekilen arka yüz (MRZ) fotoğrafı, girilen veya okunan kart erişim bilgileri (**Seri No / CAN**, **Doğum Tarihi**, **Son Geçerlilik Tarihi**) ve NFC çipinden okunan biyometrik veriler **hiçbir şekilde diske, kalıcı hafızaya (`SharedPreferences`), önbelleğe veya konsol geçmişine kaydedilmez**.
> - Tüm veriler yalnızca anlık işlem süresince geçici bellekte (`RAM`) işlenir ve aktarım tamamlandığı anda bellekten temizlenir.

---

## Proje Yapısı

```text
├── app/                  # Android uygulaması (Kotlin, ML Kit OCR, JMRTD NFC, JP2 Decoder)
├── pc/
│   └── alici.ps1         # Bilgisayar tarafında çalışan yerel HTTP alıcı ve anlık görüntüleme sunucusu
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

---

## Kurulum ve Kullanım

### 1) Bilgisayar Tarafı
PowerShell üzerinden `pc/alici.ps1` betiğini çalıştırın:

```powershell
cd pc
powershell -ExecutionPolicy Bypass -File alici.ps1
```

- Betik çalıştığında yerel IP adresinizi ve portu (ör. `192.168.1.100:8080`) ekranda gösterir.
- Bilgisayarınızda tarayıcıdan `http://localhost:8080` adresini açın.

### 2) Telefon (Android) Tarafı
1. Projeyi **Android Studio** ile açıp telefonunuza yükleyin veya `./gradlew assembleDebug` komutuyla APK oluşturup kurun.
2. Uygulamadaki **Bilgisayar adresi** alanına bilgisayarda görünen `IP:8080` adresini girin.
3. **Kimlik Arkasını Kamerayla Tara (Otomatik)** butonuna basarak kimlik kartının arka yüzünü (alt kısımdaki 3 satırlık MRZ alanı görünecek şekilde) çekin.
   - Çok aşamalı görüntü iyileştirme ve ICAO 9303 kontrol hanesi (check-digit) doğrulaması ile MRZ bilgileri bellekte çözülür ve geçici kamera görüntüsü anında silinir.
   - Kamera ile 2 denemede okuma gerçekleşmezse **Elle Giriş** alanı otomatik açılır; bilgileri elle girebilir veya kamerayla tekrar deneyebilirsiniz.
4. Kimlik kartını telefonun arkasına (NFC anten bölgesine) yaklaştırın ve okuma tamamlanana kadar sabit tutun.
5. Okuma tamamlandığında veriler bilgisayardaki tarayıcı ekranında anlık olarak görüntülenir ve telefondaki erişim anahtarı bellekten silinir.

---

## Gereksinimler

- **Android:** Android 7.0 (API 24) veya üzeri, NFC destekli cihaz
- **Bilgisayar:** Windows PowerShell 5.1+ veya PowerShell 7+
- **Ağ:** Telefon ve bilgisayar aynı yerel ağda (Wi‑Fi veya telefonun Kişisel Erişim Noktası / Hotspot) bulunmalıdır.

