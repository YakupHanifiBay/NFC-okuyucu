package com.example.nfcreader

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import com.gemalto.jp2.JP2Decoder
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import net.sf.scuba.smartcards.CardService
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.jmrtd.BACKey
import org.jmrtd.PassportService
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.icao.DG2File
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.Security
import kotlin.concurrent.thread
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private var nfc: NfcAdapter? = null
    private lateinit var etServer: EditText
    private lateinit var btnScan: Button
    private lateinit var btnManualToggle: Button
    private lateinit var layoutManualEntry: LinearLayout
    private lateinit var etDocNo: EditText
    private lateinit var etDob: EditText
    private lateinit var etExp: EditText
    private lateinit var btnApplyManual: Button
    private lateinit var tvMrzDurum: TextView
    private lateinit var tvStatus: TextView
    private lateinit var ivPhoto: ImageView

    // Yalnızca anlık işlem süresince RAM'de tutulur; kalıcı hafızaya kaydedilmez
    @Volatile
    private var bacKey: BACKey? = null
    private var fotoUri: Uri? = null
    private var basarisizDenemeSayisi = 0

    // Aynı kartın fotoğraf denemeleri sırasında kısmi okunan alanları geçici tutar
    private var kismiDoc: String? = null
    private var kismiDob: String? = null
    private var kismiExp: String? = null

    private val kameraBaslatici = registerForActivityResult(ActivityResultContracts.TakePicture()) { basarili ->
        val f = File(cacheDir, "mrz.jpg")
        val uri = fotoUri ?: FileProvider.getUriForFile(this, "$packageName.provider", f)
        if (basarili) {
            taraMrz(uri)
        } else {
            geciciFotoDosyasiniSil()
            status("Kamera çekimi iptal edildi.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        etServer = findViewById(R.id.etServer)
        btnScan = findViewById(R.id.btnScan)
        btnManualToggle = findViewById(R.id.btnManualToggle)
        layoutManualEntry = findViewById(R.id.layoutManualEntry)
        etDocNo = findViewById(R.id.etDocNo)
        etDob = findViewById(R.id.etDob)
        etExp = findViewById(R.id.etExp)
        btnApplyManual = findViewById(R.id.btnApplyManual)
        tvMrzDurum = findViewById(R.id.tvMrzDurum)
        tvStatus = findViewById(R.id.tvStatus)
        ivPhoto = findViewById(R.id.ivPhoto)

        // Önceki sürümlerden kalmış olabilecek kayıtları temizle
        // Kimlik/kart verileri kaydedilmez; eski sürümlerden kalanlar silinir.
        // Yalnızca kişisel olmayan bilgisayar adresi (IP:port) hatırlanır, böylece her seferinde yazılmaz.
        val prefs = getPreferences(MODE_PRIVATE)
        prefs.edit().remove("doc").remove("dob").remove("exp").commit()
        etServer.setText(prefs.getString("server", ""))

        btnScan.setOnClickListener {
            kamerayiBaslat()
        }

        btnManualToggle.setOnClickListener {
            if (layoutManualEntry.visibility == View.VISIBLE) {
                layoutManualEntry.visibility = View.GONE
                btnManualToggle.text = "⌨️ Kart Erişim (CAN / MRZ) Bilgilerini Elle Gir"
            } else {
                manuelGirisiGoster()
            }
        }

        btnApplyManual.setOnClickListener {
            elleGirilenBilgileriUygula(kullaniciUyarisiGoster = true)
        }

        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        nfc = NfcAdapter.getDefaultAdapter(this)
        if (nfc == null) status("Bu cihazda NFC yok.")
        else if (nfc?.isEnabled == false) status("Lütfen ayarlardan NFC'yi açın.")
    }

    private fun kamerayiBaslat() {
        val f = File(cacheDir, "mrz.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.provider", f)
        fotoUri = uri
        kameraBaslatici.launch(uri)
    }

    private fun geciciFotoDosyasiniSil() {
        try {
            File(cacheDir, "mrz.jpg").apply { if (exists()) delete() }
            File(cacheDir, "mrz_temp.jpg").apply { if (exists()) delete() }
        } catch (_: Throwable) {
        }
    }

    private fun hassasVerileriTemizle() {
        bacKey = null
        kismiDoc = null
        kismiDob = null
        kismiExp = null
        basarisizDenemeSayisi = 0
        geciciFotoDosyasiniSil()
        runOnUiThread {
            etDocNo.text?.clear()
            etDob.text?.clear()
            etExp.text?.clear()
            tvMrzDurum.text = "Kart erişim anahtarı: Bellekten temizlendi (Kayıt tutulmaz)"
        }
    }

    private fun manuelGirisiGoster() {
        kismiDoc?.let { if (etDocNo.text.isNullOrBlank()) etDocNo.setText(it) }
        kismiDob?.let { if (etDob.text.isNullOrBlank()) etDob.setText(it) }
        kismiExp?.let { if (etExp.text.isNullOrBlank()) etExp.setText(it) }
        layoutManualEntry.visibility = View.VISIBLE
        btnManualToggle.text = "🔼 Elle Giriş Alanını Gizle"
    }

    private fun elleGirilenBilgileriUygula(kullaniciUyarisiGoster: Boolean): Boolean {
        val rawDoc = etDocNo.text.toString().trim().uppercase().replace(" ", "")
        val doc = normalizeManualDocNo(rawDoc)
        val dob = kullaniciTarihiniCoz(etDob.text.toString())
        val exp = kullaniciTarihiniCoz(etExp.text.toString())

        if (doc == null) {
            if (kullaniciUyarisiGoster) {
                status("Lütfen 9 haneli Seri No / CAN bilgisini doğru girin (ör. A01B23456).")
                etDocNo.requestFocus()
            }
            return false
        }
        if (dob == null) {
            if (kullaniciUyarisiGoster) {
                status("Lütfen Doğum Tarihini GG.AA.YYYY (ör. 12.03.1995) veya YYAAGG (ör. 950312) olarak girin.")
                etDob.requestFocus()
            }
            return false
        }
        if (exp == null) {
            if (kullaniciUyarisiGoster) {
                status("Lütfen Son Geçerlilik Tarihini GG.AA.YYYY (ör. 05.11.2032) veya YYAAGG (ör. 321105) olarak girin.")
                etExp.requestFocus()
            }
            return false
        }

        etDocNo.setText(doc)
        anahtariBellekteHazirla(doc, dob, exp, "Kart anahtarı hazır ($doc — Kaydedilmez)")
        status("Elle girilen anahtar hazır! Şimdi kartı telefonun arkasına (NFC) yaklaştırın.")
        return true
    }

    private fun anahtariBellekteHazirla(doc: String, dob: String, exp: String, durumMesaji: String) {
        bacKey = BACKey(doc, dob, exp)
        basarisizDenemeSayisi = 0
        kismiDoc = doc
        kismiDob = dob
        kismiExp = exp
        etDocNo.setText(doc)
        etDob.setText(dob)
        etExp.setText(exp)
        tvMrzDurum.text = durumMesaji
        btnScan.text = "📷 Kimlik Arkasını Kamerayla Tara (Otomatik)"
    }

    private fun taraMrz(uri: Uri) {
        status("Görüntü inceleniyor...")
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        val ilkImage = try {
            InputImage.fromFilePath(this, uri)
        } catch (e: Throwable) {
            null
        }

        if (ilkImage != null) {
            recognizer.process(ilkImage)
                .addOnSuccessListener { visionText ->
                    val sonuc = mrzMetniniCoz(visionText.text)
                    if (sonuc.doc != null) kismiDoc = sonuc.doc
                    if (sonuc.dob != null) kismiDob = sonuc.dob
                    if (sonuc.exp != null) kismiExp = sonuc.exp

                    val doc = kismiDoc
                    val dob = kismiDob
                    val exp = kismiExp

                    if (doc != null && dob != null && exp != null) {
                        geciciFotoDosyasiniSil()
                        anahtariBellekteHazirla(doc, dob, exp, "Kart anahtarı kameradan alındı ($doc)")
                        status("Anahtar hazır! Şimdi kartı telefonun arkasına (NFC) yaklaştırın.")
                    } else {
                        ikinciAsamaGelismisTara()
                    }
                }
                .addOnFailureListener {
                    ikinciAsamaGelismisTara()
                }
        } else {
            ikinciAsamaGelismisTara()
        }
    }

    /**
     * İlk geçişte tüm alanlar okunamadıysa fotoğrafı bellekte tek tek kontrastlayıp/kırpıp/döndürerek dener.
     */
    private fun ikinciAsamaGelismisTara() {
        thread {
            val orijinal = try {
                yukleVeBoyutlandirBitmap(1600)
            } catch (_: Throwable) {
                null
            } finally {
                geciciFotoDosyasiniSil()
            }

            if (orijinal == null) {
                runOnUiThread { okumaBasarisizOldu(null) }
                return@thread
            }

            runOnUiThread {
                adimAdimVaryantTara(orijinal, 0)
            }
        }
    }

    private fun adimAdimVaryantTara(kaynak: Bitmap, adim: Int) {
        if (adim > 4) {
            if (!kaynak.isRecycled) kaynak.recycle()
            okumaBasarisizOldu(null)
            return
        }

        val varyant: Bitmap? = try {
            when (adim) {
                // 0: Yüksek kontrastlı gri tonlama
                0 -> griVeKontrastliYap(kaynak, contrast = 1.6f, brightness = -20f)
                // 1: Alt %60 MRZ bölgesi kırpılmış + yüksek kontrast
                1 -> {
                    val y = (kaynak.height * 0.38f).toInt()
                    val alt = Bitmap.createBitmap(kaynak, 0, y, kaynak.width, kaynak.height - y)
                    val islenmis = griVeKontrastliYap(alt, contrast = 1.85f, brightness = -35f)
                    if (alt != kaynak && alt != islenmis) alt.recycle()
                    islenmis
                }
                // 2: Düşük ışık / parlama için güçlü kontrast
                2 -> griVeKontrastliYap(kaynak, contrast = 2.2f, brightness = -55f)
                // 3: 180 derece döndürülmüş (kart ters tutulduysa)
                3 -> dondurBitmap(kaynak, 180f)
                // 4: 90 derece döndürülmüş (kart dik tutulduysa)
                else -> dondurBitmap(kaynak, 90f)
            }
        } catch (_: Throwable) {
            null
        }

        if (varyant == null) {
            adimAdimVaryantTara(kaynak, adim + 1)
            return
        }

        val image = InputImage.fromBitmap(varyant, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                if (varyant != kaynak && !varyant.isRecycled) varyant.recycle()
                val sonuc = mrzMetniniCoz(visionText.text)
                if (sonuc.doc != null) kismiDoc = sonuc.doc
                if (sonuc.dob != null) kismiDob = sonuc.dob
                if (sonuc.exp != null) kismiExp = sonuc.exp

                val doc = kismiDoc
                val dob = kismiDob
                val exp = kismiExp

                if (doc != null && dob != null && exp != null) {
                    if (!kaynak.isRecycled) kaynak.recycle()
                    anahtariBellekteHazirla(doc, dob, exp, "Kart anahtarı kameradan alındı ($doc)")
                    status("Anahtar hazır! Şimdi kartı telefonun arkasına (NFC) yaklaştırın.")
                } else {
                    adimAdimVaryantTara(kaynak, adim + 1)
                }
            }
            .addOnFailureListener {
                if (varyant != kaynak && !varyant.isRecycled) varyant.recycle()
                adimAdimVaryantTara(kaynak, adim + 1)
            }
    }

    private fun okumaBasarisizOldu(hataDetayi: String?) {
        geciciFotoDosyasiniSil()
        basarisizDenemeSayisi++
        val bulunanlar = mutableListOf<String>()
        kismiDoc?.let { bulunanlar.add("Seri No: $it") }
        kismiDob?.let { bulunanlar.add("Doğum: $it") }
        kismiExp?.let { bulunanlar.add("Son Geç.: $it") }
        val kismiBilgi = if (bulunanlar.isNotEmpty()) " (Kısmen okunan: ${bulunanlar.joinToString(", ")})" else ""

        if (basarisizDenemeSayisi >= 2) {
            manuelGirisiGoster()
            btnScan.text = "📷 Fotoğrafla Tekrar Dene (Kamera)"
            status(
                "2 denemede arka yüz fotoğrafı tam okunamadı.$kismiBilgi\n" +
                "İsterseniz aşağıdan CAN / MRZ bilgilerini elle girebilir, isterseniz 'Fotoğrafla Tekrar Dene' ile yeniden çekebilirsiniz."
            )
            AlertDialog.Builder(this)
                .setTitle("Kart Bilgileri Okunamadı ($basarisizDenemeSayisi Deneme)")
                .setMessage(
                    "Kimlik arka yüz fotoğrafından erişim bilgileri $basarisizDenemeSayisi denemede tam okunamadı.$kismiBilgi\n\n" +
                    "Bilgileri elle girmek mi yoksa fotoğrafla tekrar denemek mi istersiniz?"
                )
                .setPositiveButton("⌨️ Elle Gir") { _, _ ->
                    manuelGirisiGoster()
                    when {
                        etDocNo.text.isNullOrBlank() -> etDocNo.requestFocus()
                        etDob.text.isNullOrBlank() -> etDob.requestFocus()
                        else -> etExp.requestFocus()
                    }
                }
                .setNeutralButton("📷 Fotoğrafla Tekrar Dene") { _, _ ->
                    kamerayiBaslat()
                }
                .show()
        } else {
            val ekHata = if (hataDetayi != null) "\n($hataDetayi)" else ""
            status(
                "$basarisizDenemeSayisi. denemede kartın arka yüzü tam okunamadı.$kismiBilgi\n" +
                "Lütfen kartın arkasındaki alt 3 satırı (MRZ) parlama olmadan net çekerek tekrar deneyin. (Deneme: $basarisizDenemeSayisi/2)$ekHata"
            )
        }
    }

    private fun yukleVeBoyutlandirBitmap(maxBoyut: Int): Bitmap? {
        val f = File(cacheDir, "mrz.jpg")
        if (!f.exists() || f.length() == 0L) return null
        val path = f.absolutePath

        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, boundsOpts)
        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

        var sampleSize = 1
        while (max(boundsOpts.outWidth, boundsOpts.outHeight) / sampleSize > maxBoyut * 2) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val hamBitmap = BitmapFactory.decodeFile(path, decodeOpts) ?: return null

        val exifDerece = try {
            val exif = ExifInterface(path)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: Throwable) {
            0f
        }

        val enUzunKenar = max(hamBitmap.width, hamBitmap.height)
        val olcek = if (enUzunKenar > maxBoyut) maxBoyut.toFloat() / enUzunKenar.toFloat() else 1f
        if (exifDerece == 0f && olcek == 1f) return hamBitmap

        val matrix = Matrix()
        if (olcek != 1f) matrix.postScale(olcek, olcek)
        if (exifDerece != 0f) matrix.postRotate(exifDerece)
        val duzeltilmis = Bitmap.createBitmap(hamBitmap, 0, 0, hamBitmap.width, hamBitmap.height, matrix, true)
        if (duzeltilmis != hamBitmap) hamBitmap.recycle()
        return duzeltilmis
    }

    private fun griVeKontrastliYap(kaynak: Bitmap, contrast: Float, brightness: Float): Bitmap {
        val sonuc = Bitmap.createBitmap(kaynak.width, kaynak.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sonuc)
        val griMatrix = ColorMatrix().apply { setSaturation(0f) }
        val kontrastMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, brightness,
                0f, contrast, 0f, 0f, brightness,
                0f, 0f, contrast, 0f, brightness,
                0f, 0f, 0f, 1f, 0f
            )
        )
        griMatrix.postConcat(kontrastMatrix)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(griMatrix) }
        canvas.drawBitmap(kaynak, 0f, 0f, paint)
        return sonuc
    }

    private fun dondurBitmap(kaynak: Bitmap, derece: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(derece) }
        return Bitmap.createBitmap(kaynak, 0, 0, kaynak.width, kaynak.height, matrix, true)
    }

    private data class MrzSonuc(val doc: String?, val dob: String?, val exp: String?)

    /**
     * T.C. Kimlik Kartı arka yüz MRZ metnini hem orijinal doğrudan şablonlarla
     * hem de esnek OCR karakter düzeltmesi + kontrol hanesi doğrulamasıyla çözümler.
     */
    private fun mrzMetniniCoz(hamMetin: String): MrzSonuc {
        val ustMetin = hamMetin.uppercase()
            .replace('«', '<')
            .replace('‹', '<')
            .replace('＜', '<')

        val hamSatirlar = ustMetin.replace(" ", "").lines()
        val temizSatirlar = ustMetin.lines()
            .map { it.replace(Regex("[^A-Z0-9<]"), "") }
            .filter { it.length >= 4 }

        // Bölünmüş MRZ satırlarını yakalamak için birleştirilmiş metni de aday listesine ekle
        val tumAdaySatirlar = mutableListOf<String>()
        tumAdaySatirlar.addAll(hamSatirlar)
        tumAdaySatirlar.addAll(temizSatirlar)
        if (temizSatirlar.isNotEmpty()) {
            tumAdaySatirlar.add(temizSatirlar.joinToString(""))
        }

        var bulunanDoc: String? = null
        var enIyiDocSkor = -1

        var bulunanDob: String? = null
        var bulunanExp: String? = null
        var enIyiTarihSkor = -1

        // A) Doğrudan klasik şablonlar (ilk çalışan temel şablonlar + pozisyon düzeltmesi)
        val klasikDocRegex = Regex("I[D<CKET1L|]?T[UV0O][R8]([A-Z0-9]{9})")
        val klasikFallback = Regex("([A-Z][0-9OISDGBLZ]{2}[A-Z][0-9OISDGBLZ]{5})")
        val klasikTarihRegex = Regex("(\\d{6})[0-9][MF<KXC](\\d{6})[0-9]?")

        for (line in tumAdaySatirlar) {
            klasikDocRegex.find(line)?.let { m ->
                val duz = duzeltSeriNo(m.groupValues[1])
                if (duz != null && enIyiDocSkor < 10) {
                    bulunanDoc = duz
                    enIyiDocSkor = 10
                }
            }
            if (bulunanDoc == null) {
                for (m in klasikFallback.findAll(line)) {
                    val duz = duzeltSeriNo(m.groupValues[1])
                    if (duz != null && enIyiDocSkor < 5) {
                        bulunanDoc = duz
                        enIyiDocSkor = 5
                    }
                }
            }

            // Orjinal satırda M/F/< karakterini koruyarak tarih eşleştir
            val normTarihSatiri = StringBuilder(line.length)
            for (idx in line.indices) {
                val c = line[idx]
                normTarihSatiri.append(if (c in "MF<") c else rakamaCevir(c))
            }
            klasikTarihRegex.find(normTarihSatiri.toString())?.let { m ->
                val d1 = m.groupValues[1]
                val d2 = m.groupValues[2]
                if (gecerliTarihMi(d1) && gecerliTarihMi(d2)) {
                    bulunanDob = d1
                    bulunanExp = d2
                    enIyiTarihSkor = 10
                }
            }
            if (bulunanDob == null) {
                klasikTarihRegex.find(line.replace('O', '0'))?.let { m ->
                    val d1 = m.groupValues[1]
                    val d2 = m.groupValues[2]
                    if (gecerliTarihMi(d1) && gecerliTarihMi(d2)) {
                        bulunanDob = d1
                        bulunanExp = d2
                        enIyiTarihSkor = 9
                    }
                }
            }
        }

        // B) Gelişmiş Seri No Arama (OCR harf/rakam karışıklıkları ve ICAO kontrol hanesi puanlaması)
        val esnekTurRegex = Regex("(?:[I1L|!][D<CKETX]?)?T[UV0O][R8]([A-Z0-9]{9})([A-Z0-9<]?)")
        for (line in temizSatirlar + listOf(temizSatirlar.joinToString(""))) {
            for (match in esnekTurRegex.findAll(line)) {
                val ham9 = match.groupValues[1]
                val chkChar = match.groupValues[2].firstOrNull()
                degerlendirDocAdayi(ham9, chkChar, prefixBonus = 4)?.let { (aday, skor) ->
                    if (skor > enIyiDocSkor) {
                        enIyiDocSkor = skor
                        bulunanDoc = aday
                    }
                }
            }

            if (line.length >= 9) {
                for (i in 0..(line.length - 9)) {
                    val ham9 = line.substring(i, i + 9)
                    val chkChar = if (i + 9 < line.length) line[i + 9] else null
                    degerlendirDocAdayi(ham9, chkChar, prefixBonus = 0)?.let { (aday, skor) ->
                        if (skor > enIyiDocSkor) {
                            enIyiDocSkor = skor
                            bulunanDoc = aday
                        }
                    }
                }
            }
        }

        // C) Gelişmiş Tarih Arama (Kontrol hanesi veya cinsiyet karakteri bozuk okunsa bile 6 haneli tarihleri yakalar)
        for (line in temizSatirlar + listOf(temizSatirlar.joinToString(""))) {
            // İki 6 haneli tarih arasında 1, 2 veya 3 karakter boşluk (chk1 + M/F/<) olabilir
            for (gap in 1..3) {
                val minLen = 6 + gap + 6
                if (line.length < minLen) continue
                for (i in 0..(line.length - minLen)) {
                    val dobAday = line.substring(i, i + 6).map { rakamaCevir(it) }.joinToString("")
                    val ara = line.substring(i + 6, i + 6 + gap)
                    val expSonIndex = i + 6 + gap + 6
                    val expAday = line.substring(i + 6 + gap, expSonIndex).map { rakamaCevir(it) }.joinToString("")

                    if (!gecerliTarihMi(dobAday) || !gecerliTarihMi(expAday)) continue

                    // Son geçerlilik yılı makul aralıkta olmalı (2020 - 2045)
                    val expYil = expAday.substring(0, 2).toInt()
                    if (expYil !in 20..45) continue

                    val dobChkChar = ara.firstOrNull()?.let { rakamaCevir(it) }
                    val expChkChar = if (expSonIndex < line.length) rakamaCevir(line[expSonIndex]) else null

                    var skor = 2
                    if (dobChkChar != null && dobChkChar.isDigit() && icaoKontrolHanesi(dobAday) == dobChkChar.digitToInt()) {
                        skor += 4
                    }
                    if (expChkChar != null && expChkChar.isDigit() && icaoKontrolHanesi(expAday) == expChkChar.digitToInt()) {
                        skor += 4
                    }
                    if (ara.any { it in "MF<" }) {
                        skor += 3
                    }

                    val sonrasi = if (expSonIndex + 1 <= line.length) line.substring(expSonIndex) else ""
                    if (sonrasi.contains("TUR") || sonrasi.contains("T0R") || sonrasi.contains("<<")) {
                        skor += 3
                    }

                    if (skor > enIyiTarihSkor) {
                        enIyiTarihSkor = skor
                        bulunanDob = dobAday
                        bulunanExp = expAday
                    }
                }
            }
        }

        // D) Satırlar tamamen ayrılmışsa kontrol hanesi doğrulamalı tekil 7 haneli tarih bloklarını bul
        if (bulunanDob == null || bulunanExp == null) {
            val dogrulanmisTarihler = mutableListOf<String>()
            for (line in temizSatirlar) {
                if (line.length >= 7) {
                    for (i in 0..(line.length - 7)) {
                        val blok = line.substring(i, i + 7).map { rakamaCevir(it) }.joinToString("")
                        if (blok.all { it.isDigit() }) {
                            val t = blok.substring(0, 6)
                            val chk = blok[6].digitToInt()
                            if (gecerliTarihMi(t) && icaoKontrolHanesi(t) == chk && !dogrulanmisTarihler.contains(t)) {
                                dogrulanmisTarihler.add(t)
                            }
                        }
                    }
                }
            }
            if (dogrulanmisTarihler.size >= 2) {
                val olasiExp = dogrulanmisTarihler.lastOrNull { it.substring(0, 2).toInt() in 22..45 }
                val olasiDob = dogrulanmisTarihler.firstOrNull { it != olasiExp }
                if (bulunanDob == null && olasiDob != null) bulunanDob = olasiDob
                if (bulunanExp == null && olasiExp != null) bulunanExp = olasiExp
            }
        }

        // E) Noktalı tarih (GG.AA.YYYY) yedek kontrolü
        if (bulunanDob == null || bulunanExp == null) {
            val noktaliTarihRegex = Regex("(\\d{2})[./-](\\d{2})[./-](\\d{4})")
            val bulunanNoktali = noktaliTarihRegex.findAll(ustMetin)
                .mapNotNull { kullaniciTarihiniCoz(it.value) }
                .distinct()
                .toList()
            if (bulunanNoktali.size >= 2) {
                if (bulunanDob == null) bulunanDob = bulunanNoktali[0]
                if (bulunanExp == null) bulunanExp = bulunanNoktali[1]
            }
        }

        return MrzSonuc(bulunanDoc, bulunanDob, bulunanExp)
    }

    private fun duzeltSeriNo(ham9: String): String? {
        if (ham9.length != 9 || ham9.contains('<')) return null
        val chars = CharArray(9)
        for (i in 0 until 9) {
            val c = ham9[i]
            chars[i] = if (i == 0 || i == 3) harfeCevir(c) else rakamaCevir(c)
        }
        val duz = String(chars)
        if (Regex("^[A-Z][0-9]{2}[A-Z][0-9]{5}$").matches(duz)) return duz
        // Eğer 4. karakter rakam olan özel/eski seri ise:
        val chars2 = CharArray(9)
        for (i in 0 until 9) {
            val c = ham9[i]
            chars2[i] = if (i == 0) harfeCevir(c) else rakamaCevir(c)
        }
        val duz2 = String(chars2)
        return if (Regex("^[A-Z][0-9]{8}$").matches(duz2)) duz2 else null
    }

    private fun degerlendirDocAdayi(
        ham9: String,
        kontrolKarakteri: Char?,
        prefixBonus: Int
    ): Pair<String, Int>? {
        if (ham9.length != 9 || ham9.contains('<')) return null
        if (ham9.all { it.isDigit() }) return null

        val duz = duzeltSeriNo(ham9) ?: return null
        var farkSayisi = 0
        for (i in 0 until 9) {
            if (ham9[i] != duz[i]) farkSayisi++
        }
        if (prefixBonus == 0 && farkSayisi > 2) return null

        val beklenenChk = icaoKontrolHanesi(duz)
        val okunanChk = kontrolKarakteri?.let { rakamaCevir(it) }?.takeIf { it.isDigit() }?.digitToInt()

        var skor = prefixBonus + (3 - farkSayisi.coerceAtMost(3))
        if (okunanChk != null && okunanChk == beklenenChk) {
            skor += 6
        }
        return Pair(duz, skor)
    }

    private fun normalizeManualDocNo(raw: String): String? {
        if (raw.length != 9) return null
        return duzeltSeriNo(raw) ?: if (Regex("^[A-Z0-9]{9}$").matches(raw)) raw else null
    }

    private fun kullaniciTarihiniCoz(girdi: String): String? {
        val temiz = girdi.trim()
        val parcalar = temiz.split('.', '/', '-', ' ').filter { it.isNotEmpty() }
        if (parcalar.size == 3) {
            val p0 = parcalar[0]
            val p1 = parcalar[1].padStart(2, '0')
            val p2 = parcalar[2]
            if (p0.length <= 2 && (p2.length == 4 || p2.length == 2)) {
                val gg = p0.padStart(2, '0')
                val yy = p2.takeLast(2)
                val yyaagg = "$yy$p1$gg"
                if (gecerliTarihMi(yyaagg)) return yyaagg
            }
            if (p0.length == 4 && p2.length <= 2) {
                val yy = p0.takeLast(2)
                val gg = p2.padStart(2, '0')
                val yyaagg = "$yy$p1$gg"
                if (gecerliTarihMi(yyaagg)) return yyaagg
            }
        }

        val sadeceRakam = temiz.filter { it.isDigit() }
        if (sadeceRakam.length == 6 && gecerliTarihMi(sadeceRakam)) {
            return sadeceRakam
        }
        if (sadeceRakam.length == 8) {
            val gg = sadeceRakam.substring(0, 2)
            val aa = sadeceRakam.substring(2, 4)
            val yy = sadeceRakam.substring(6, 8)
            val aday1 = "$yy$aa$gg"
            if (gecerliTarihMi(aday1)) return aday1

            val aday2 = sadeceRakam.substring(2, 8)
            if (gecerliTarihMi(aday2)) return aday2
        }
        return null
    }

    private fun gecerliTarihMi(yymmdd: String): Boolean {
        if (yymmdd.length != 6 || !yymmdd.all { it.isDigit() }) return false
        val mm = yymmdd.substring(2, 4).toInt()
        val dd = yymmdd.substring(4, 6).toInt()
        return mm in 1..12 && dd in 1..31
    }

    private fun rakamaCevir(c: Char): Char = when (c) {
        'O', 'D', 'Q', 'U' -> '0'
        'I', 'L', '|', '!' -> '1'
        'Z' -> '2'
        'S', '$' -> '5'
        'G' -> '6'
        'B', '&' -> '8'
        else -> c
    }

    private fun harfeCevir(c: Char): Char = when (c) {
        '0' -> 'O'
        '1', '|', '!' -> 'I'
        '2' -> 'Z'
        '4' -> 'A'
        '5' -> 'S'
        '6' -> 'G'
        '8' -> 'B'
        else -> c
    }

    private fun icaoKontrolHanesi(s: String): Int {
        val agirliklar = intArrayOf(7, 3, 1)
        var toplam = 0
        for (i in s.indices) {
            val ch = s[i]
            val deger = when (ch) {
                in '0'..'9' -> ch - '0'
                in 'A'..'Z' -> ch - 'A' + 10
                '<' -> 0
                else -> 0
            }
            toplam += deger * agirliklar[i % 3]
        }
        return toplam % 10
    }

    override fun onResume() {
        super.onResume()
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE
        )
        nfc?.enableForegroundDispatch(this, pi, null, arrayOf(arrayOf("android.nfc.tech.IsoDep")))
    }

    override fun onPause() {
        super.onPause()
        nfc?.disableForegroundDispatch(this)
    }

    override fun onDestroy() {
        if (isFinishing) {
            hassasVerileriTemizle()
        }
        ivPhoto.setImageDrawable(null)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tag: Tag = IntentCompat.getParcelableExtra(intent, NfcAdapter.EXTRA_TAG, Tag::class.java) ?: return
        if (!tag.techList.contains("android.nfc.tech.IsoDep")) return

        if (layoutManualEntry.visibility == View.VISIBLE) {
            elleGirilenBilgileriUygula(kullaniciUyarisiGoster = false)
        }

        val hamServer = etServer.text.toString().trim()
        val key = bacKey
        if (hamServer.isEmpty() || key == null) {
            status("Lütfen bilgisayar adresini girin ve kart bilgilerini kamerayla tarayın veya elle girin.")
            return
        }
        val server = sunucuAdresiniDuzenle(hamServer)
        if (server != hamServer) {
            etServer.setText(server)
        }
        getPreferences(MODE_PRIVATE).edit().putString("server", server).apply()

        status("NFC ile çipten okunuyor... kartı telefondan ayırmayın.")
        thread {
            try {
                val (json, bmp) = readCard(IsoDep.get(tag), key)
                val dogumMetni = formatTarih(json.getString("dogumTarihi"), true)
                val gecerlilikMetni = formatTarih(json.getString("sonGecerlilikTarihi"), false)

                runOnUiThread { ivPhoto.setImageBitmap(bmp) }
                status("NFC okundu, bilgisayara ($server) gönderiliyor...")
                send(server, json)

                // İşlem tamamlandığı anda girilen/taranan erişim anahtarını ve form alanlarını bellekten sil
                hassasVerileriTemizle()

                status(
                    "Tamam! Bilgiler hiçbir yere kaydedilmeden anlık olarak bilgisayara iletildi ve bellekten silindi:\n" +
                    "Doğum Tarihi (DG1): $dogumMetni\n" +
                    "Son Geçerlilik (DG1): $gecerlilikMetni"
                )
            } catch (e: java.net.SocketTimeoutException) {
                status(
                    "Bağlantı Zaman Aşımı (SocketTimeoutException -> http://$server/nfc):\n" +
                    "1) Bilgisayarda 'alici.ps1' açık mı ve adres doğru mu? (Farklı Wi-Fi ağına geçildiyse IP değişmiştir)\n" +
                    "2) Kampüs/kurumsal Wi-Fi ağları cihazlar arası iletişimi engeller; telefonun Kişisel Erişim Noktasını (Hotspot) açıp bilgisayarı telefona bağlayın.\n" +
                    "3) Windows Güvenlik Duvarı 8080 portunu engelliyor olabilir."
                )
            } catch (e: Exception) {
                status("Hata: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun sunucuAdresiniDuzenle(ham: String): String {
        var s = ham.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .trimEnd('/')
            .replace(" ", "")
        if (s.isNotEmpty() && !s.contains(':')) {
            s = "$s:8080"
        }
        return s
    }

    private fun formatTarih(s: String, dogumMu: Boolean): String {
        val yy = s.substring(0, 2).toInt()
        val mm = s.substring(2, 4)
        val dd = s.substring(4, 6)
        val yil = if (dogumMu && yy > 26) 1900 + yy else 2000 + yy
        return "$dd.$mm.$yil"
    }

    private fun readCard(isoDep: IsoDep, key: BACKey): Pair<JSONObject, Bitmap> {
        isoDep.timeout = 15000
        val service = PassportService(
            CardService.getInstance(isoDep), PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
            PassportService.DEFAULT_MAX_BLOCKSIZE, false, false
        )
        service.open()
        service.sendSelectApplet(false)
        service.doBAC(key)

        val dg1 = DG1File(service.getInputStream(PassportService.EF_DG1, PassportService.DEFAULT_MAX_BLOCKSIZE))
        val mrz = dg1.mrzInfo
        val dg2 = DG2File(service.getInputStream(PassportService.EF_DG2, PassportService.DEFAULT_MAX_BLOCKSIZE))
        val img = dg2.faceInfos.first().faceImageInfos.first()
        val raw = ByteArray(img.imageLength)
        DataInputStream(img.imageInputStream).use { it.readFully(raw) }

        val bmp: Bitmap = decodeFaceBitmap(raw)
        raw.fill(0)

        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
        val jpegBytes = out.toByteArray()
        val base64Foto = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        jpegBytes.fill(0)

        val json = JSONObject()
            .put("dogumTarihi", mrz.dateOfBirth)
            .put("sonGecerlilikTarihi", mrz.dateOfExpiry)
            .put("fotoJpegBase64", base64Foto)
        return Pair(json, bmp)
    }

    private fun decodeFaceBitmap(raw: ByteArray): Bitmap {
        BitmapFactory.decodeByteArray(raw, 0, raw.size)?.let { return it }
        JP2Decoder(raw).decode()?.let { return it }
        for (i in 0 until raw.size - 4) {
            val b0 = raw[i].toInt() and 0xFF
            val b1 = raw[i + 1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xD8 && (raw[i + 2].toInt() and 0xFF) == 0xFF) {
                BitmapFactory.decodeByteArray(raw, i, raw.size - i)?.let { return it }
            }
            val isJp2 = b0 == 0x00 && b1 == 0x00 && (raw[i + 2].toInt() and 0xFF) == 0x00 && (raw[i + 3].toInt() and 0xFF) == 0x0C
            val isJ2k = b0 == 0xFF && b1 == 0x4F && (raw[i + 2].toInt() and 0xFF) == 0xFF && (raw[i + 3].toInt() and 0xFF) == 0x51
            if (isJp2 || isJ2k) {
                JP2Decoder(raw.copyOfRange(i, raw.size)).decode()?.let { return it }
            }
        }
        throw IllegalStateException("Fotoğraf çözülemedi (${raw.size} bayt)")
    }

    private fun send(server: String, json: JSONObject) {
        val bodyBytes = json.toString().toByteArray(Charsets.UTF_8)
        var sonHata: Exception? = null
        for (deneme in 1..2) {
            try {
                val c = URL("http://$server/nfc").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.doOutput = true
                c.useCaches = false
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.setFixedLengthStreamingMode(bodyBytes.size)
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.setRequestProperty("Accept-Charset", "UTF-8")
                c.setRequestProperty("Cache-Control", "no-store, no-cache, must-revalidate")
                c.setRequestProperty("Pragma", "no-cache")
                c.setRequestProperty("Connection", "close")
                c.outputStream.use { it.write(bodyBytes) }
                if (c.responseCode != 200) {
                    val hataMesaji = c.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                    throw IllegalStateException("Sunucu yanıtı: ${c.responseCode} $hataMesaji".trim())
                }
                bodyBytes.fill(0)
                return
            } catch (e: Exception) {
                sonHata = e
            }
        }
        bodyBytes.fill(0)
        throw sonHata ?: IllegalStateException("Gönderim başarısız")
    }

    private fun status(s: String) = runOnUiThread { tvStatus.text = s }
}
