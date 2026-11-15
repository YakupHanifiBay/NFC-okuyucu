# Telefondan gelen NFC verisini alır ve tarayıcıda (Doğum Tarihi, Son Geçerlilik Tarihi, Kimlik Fotoğrafı) yalnızca anlık gösterir.
# Hiçbir kişisel veri diske, konsol geçmişine veya tarayıcı önbelleğine kaydedilmez.
# Çalıştırma:  powershell -ExecutionPolicy Bypass -File alici.ps1

[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = [System.Text.Encoding]::UTF8
$utf8NoBom                = New-Object System.Text.UTF8Encoding($false)

$port = 8080
$global:veri = $null

# Windows Güvenlik Duvarı'nda 8080 portu için kural yoksa (Yönetici ise) otomatik açmayı dene
try {
    $kural = Get-NetFirewallRule -DisplayName "NFC_Okuyucu_8080" -ErrorAction SilentlyContinue
    if (-not $kural) {
        New-NetFirewallRule -DisplayName "NFC_Okuyucu_8080" -Direction Inbound -LocalPort $port -Protocol TCP -Action Allow -Profile Any -ErrorAction Stop | Out-Null
        Write-Host "Güvenlik duvarı izni (TCP $port) eklendi." -ForegroundColor Green
    }
} catch {
    Write-Host "Not: Telefondan bağlanırken SocketTimeoutException alırsanız PowerShell'i Yönetici olarak açıp şu komutu bir kez çalıştırın:" -ForegroundColor DarkYellow
    Write-Host "  netsh advfirewall firewall add rule name=`"NFC_Okuyucu_8080`" dir=in action=allow protocol=TCP localport=8080" -ForegroundColor Gray
}

$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Any, $port)
$listener.Start()

$ips = @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.*' } | ForEach-Object { $_.IPAddress })
if (-not $ips -or $ips.Count -eq 0) {
    $ips = @([System.Net.Dns]::GetHostAddresses([System.Net.Dns]::GetHostName()) | Where-Object { $_.AddressFamily -eq 'InterNetwork' -and $_.IPAddressToString -notlike '127.*' } | ForEach-Object { $_.IPAddressToString })
}
$adresListesi = ($ips | ForEach-Object { "${_}:$port" }) -join ' veya '
Write-Host "Telefon uygulamasına şu güncel adresi yazın: $adresListesi" -ForegroundColor Green
Write-Host "Bilgisayarda açın: http://localhost:$port  (Çıkış: Ctrl+C)" -ForegroundColor Yellow
Write-Host "Gizlilik Modu Aktif: Alınan hiçbir kimlik bilgisi veya fotoğraf kaydedilmez ve loglanmaz." -ForegroundColor Gray

function Tarih($s) {
    $yy = [int]$s.Substring(0,2); $mm = $s.Substring(2,2); $dd = $s.Substring(4,2)
    return "$dd.$mm.$(2000 + $yy)"
}

function DogumTarih($s) {
    $yy = [int]$s.Substring(0,2)
    $yil = if ($yy -gt ([DateTime]::Now.Year % 100)) { 1900 + $yy } else { 2000 + $yy }
    return "$($s.Substring(4,2)).$($s.Substring(2,2)).$yil"
}

function Yanit($stream, $code, $type, [byte[]]$body) {
    $h = "HTTP/1.1 $code OK`r`nContent-Type: $type`r`nCache-Control: no-store, no-cache, must-revalidate, max-age=0`r`nPragma: no-cache`r`nExpires: 0`r`nContent-Length: $($body.Length)`r`nConnection: close`r`n`r`n"
    $hb = $utf8NoBom.GetBytes($h)
    $stream.Write($hb, 0, $hb.Length)
    $stream.Write($body, 0, $body.Length)
}

try {
    while ($true) {
        $client = $listener.AcceptTcpClient()
        try {
            $stream = $client.GetStream()
            # Tarayıcıların açtığı boş (preconnect) soketlerin sunucuyu 5 sn kilitlememesi için ilk bayt zaman aşımı kısa tutulur
            $stream.ReadTimeout = 2500
            $stream.WriteTimeout = 5000
            $buf = New-Object byte[] 65536
            $ms = New-Object IO.MemoryStream
            $headerEnd = -1; $len = 0; $headerByteCount = 0
            while ($true) {
                $n = try { $stream.Read($buf, 0, $buf.Length) } catch { 0 }
                if ($n -le 0) { break }
                # İlk veri geldikten sonra gövdenin tamamı için zaman aşımını 5 saniyeye çıkar
                $stream.ReadTimeout = 5000
                $ms.Write($buf, 0, $n)
                if ($headerEnd -lt 0) {
                    $bytesSoFar = $ms.ToArray()
                    $txt = $utf8NoBom.GetString($bytesSoFar)
                    $headerEnd = $txt.IndexOf("`r`n`r`n")
                    if ($headerEnd -ge 0) {
                        $headerByteCount = $utf8NoBom.GetByteCount($txt.Substring(0, $headerEnd + 4))
                        if ($txt -match '(?i)Content-Length:\s*(\d+)') { $len = [int]$Matches[1] }
                    }
                }
                if ($headerEnd -ge 0 -and ($ms.Length - $headerByteCount) -ge $len) { break }
            }
            if ($ms.Length -eq 0) {
                $ms.Dispose()
                continue
            }
            $allBytes = $ms.ToArray()
            $satir = ($utf8NoBom.GetString($allBytes, 0, [Math]::Min(128, $allBytes.Length))).Split("`r`n")[0]

            if ($satir -like 'POST /nfc*') {
                $govde = $utf8NoBom.GetString($allBytes, $headerByteCount, $allBytes.Length - $headerByteCount)
                $global:veri = $govde | ConvertFrom-Json
                $govde = $null
                Write-Host "$(Get-Date -Format T) Kart verisi anlık olarak alındı (kayıt tutulmadı)." -ForegroundColor Cyan
                Yanit $stream 200 'text/plain; charset=utf-8' ($utf8NoBom.GetBytes('ok'))
            }
            elseif ($satir -like 'POST /temizle*') {
                $global:veri = $null
                Yanit $stream 200 'text/plain; charset=utf-8' ($utf8NoBom.GetBytes('ok'))
            }
            elseif ($satir -like 'GET /veri*') {
                if ($global:veri) {
                    $o = @{
                        dogum = DogumTarih $global:veri.dogumTarihi
                        gecerlilik = Tarih $global:veri.sonGecerlilikTarihi
                        foto = $global:veri.fotoJpegBase64
                    } | ConvertTo-Json
                    $global:veri = $null
                } else { $o = '{}' }
                Yanit $stream 200 'application/json; charset=utf-8' ($utf8NoBom.GetBytes($o))
            }
            else {
                $html = @'
<!doctype html><html lang="tr"><head><meta charset="utf-8"><title>NFC Kimlik Bilgileri</title>
<style>body{font-family:sans-serif;max-width:480px;margin:40px auto}img{width:180px;border:1px solid #888;border-radius:6px}td{padding:8px 12px}button{margin-top:12px;padding:8px 14px;cursor:pointer}</style></head>
<body>
<h2>NFC ile Okunan Kimlik Bilgileri</h2>
<p style="color:#555;font-size:13px">Hi&#231;bir bilgi veya foto&#287;raf diske kaydedilmez; yaln&#305;zca anl&#305;k g&#246;r&#252;nt&#252;lenir.</p>
<div id="b">Telefondan kart okutulmas&#305; bekleniyor...</div>
<script>
async function temizle(){
  await fetch('/temizle',{method:'POST',cache:'no-store'});
  document.getElementById('b').innerHTML='Ekrandaki ve bellekteki veriler temizlendi. Yeni kart bekleniyor...';
}
async function g(){try{const r=await fetch('/veri',{cache:'no-store',headers:{'Accept':'application/json; charset=utf-8'}});const d=await r.json();
if(d.dogum){document.getElementById('b').innerHTML=
`<img src="data:image/jpeg;base64,${d.foto}"><table><tr><td><b>Do&#287;um Tarihi</b></td><td>${d.dogum}</td></tr><tr><td><b>Son Ge&#231;erlilik Tarihi</b></td><td>${d.gecerlilik}</td></tr></table><button onclick="temizle()">Ekrandaki Bilgileri Sil</button>`}}catch(e){}}
window.addEventListener('beforeunload',()=>{navigator.sendBeacon('/temizle');});
setInterval(g,1500);g();
</script>
</body></html>
'@
                Yanit $stream 200 'text/html; charset=utf-8' ($utf8NoBom.GetBytes($html))
            }
            [Array]::Clear($allBytes, 0, $allBytes.Length)
            $ms.Dispose()
        } catch { Write-Host "Bağlantı hatası oluştu." -ForegroundColor Red }
        finally { $client.Close() }
    }
} finally {
    $global:veri = $null
    $listener.Stop()
}
