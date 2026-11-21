# Memasang CleanYourTV Sola, menjadikannya Home, lalu (kecuali -NoReboot) me-reboot TV
# dan memeriksa sendiri bahwa Home kembali normal: tetap launcher ini, tidak crash, dan
# menulis screenshot home-setelah-reboot.png di folder ini.
param(
    [Parameter(Mandatory = $true)][string]$Device,
    [string]$Apk = '',
    [switch]$Fresh,
    [switch]$NoReboot
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $Apk) {
    # Pakai APK CleanYourTV terbaru yang ada di folder ini, jadi folder yang disalin tetap jalan.
    $newest = Get-ChildItem -LiteralPath $root -Filter 'CleanYourTV-Sola-*.apk' |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($newest) { $Apk = $newest.FullName }
}

$pkg = 'com.gathin.tvhome'
$homeActivity = "$pkg/.MainActivity"

function Fail([string]$message) {
    Write-Host ""
    Write-Host "GAGAL: $message" -ForegroundColor Red
    exit 1
}

function Step([string]$message) {
    Write-Host ""
    Write-Host "== $message" -ForegroundColor Cyan
}

# Runs adb and returns its combined output without letting stderr stop the script.
function Invoke-Adb([string[]]$Cmd) {
    $out = & adb -s $Device @Cmd 2>&1 | Out-String
    return $out.Trim()
}

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    Fail "adb tidak ditemukan. Pasang Android platform-tools dan pastikan 'adb' ada di PATH."
}

if ($Device -notmatch ':\d+$') { $Device = "${Device}:5555" }

if (-not (Test-Path -LiteralPath $Apk)) { Fail "APK tidak ditemukan: $Apk" }
$Apk = (Resolve-Path -LiteralPath $Apk).Path

Step "Menyambungkan ke $Device"
if ($Device -match '^[\d\.]+:\d+$') { Invoke-Adb @('connect', $Device) | Out-Null }
$state = (Invoke-Adb @('get-state')).Split("`n")[-1].Trim()
if ($state -ne 'device') {
    Fail "TV belum terhubung. Pastikan USB/Network debugging menyala, TV dan komputer di Wi-Fi yang sama, lalu izinkan 'Always allow' di layar TV."
}

Step "Membaca launcher yang sekarang"
$current = (Invoke-Adb @('shell', 'cmd', 'package', 'resolve-activity', '--brief', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')).Split("`n")[-1].Trim()
Write-Host "Home sekarang : $current"
$currentPkg = ($current -split '/')[0]

$stock = ''
if ($currentPkg -and $currentPkg -ne $pkg) {
    $stock = $currentPkg
    Set-Content -LiteralPath (Join-Path $root 'launcher-bawaan.txt') -Value $stock -Encoding ASCII
    Write-Host "Launcher bawaan dicatat di launcher-bawaan.txt: $stock"
}

$installed = [bool]((Invoke-Adb @('shell', 'pm', 'list', 'packages', $pkg)) -match "package:$([regex]::Escape($pkg))")

if ($installed -and $Fresh) {
    Step "Menghapus CleanYourTV yang lama (data launcher lama ikut terhapus)"
    if ($stock) { Invoke-Adb @('shell', 'pm', 'enable', $stock) | Out-Null }
    Invoke-Adb @('uninstall', $pkg) | Out-Null
    $installed = $false
}

Step "Memasang APK"
$installArgs = @('install')
if ($installed) { $installArgs += '-r' }
$installArgs += $Apk
$output = Invoke-Adb $installArgs

if ($output -match 'Success') {
    Write-Host "APK terpasang." -ForegroundColor Green
} elseif ($output -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match|INSTALL_FAILED_VERSION_DOWNGRADE') {
    Fail "Di TV sudah ada CleanYourTV dengan kunci berbeda (versi resmi). Jalankan ulang skrip ini dengan opsi -Fresh (atau jawab 'y' di pasang.bat) untuk menghapus dulu. Data launcher lama akan hilang; aplikasi lain aman."
} else {
    Fail "Instalasi gagal:`n$output"
}

Step "Memberi izin adzan dan timer tidur"
Invoke-Adb @('shell', 'appops', 'set', $pkg, 'SYSTEM_ALERT_WINDOW', 'allow') | Out-Null
Invoke-Adb @('shell', 'appops', 'set', $pkg, 'SCHEDULE_EXACT_ALARM', 'allow') | Out-Null   # Android 12+ saja
Invoke-Adb @('shell', 'appops', 'set', $pkg, 'APP_AUTO_START', 'allow') | Out-Null          # sebagian TV saja
$grant = Invoke-Adb @('shell', 'pm', 'grant', $pkg, 'android.permission.WRITE_SECURE_SETTINGS')
if ($grant -match 'Error|Exception') {
    Write-Host "Peringatan: izin timer tidur tidak bisa diberikan otomatis." -ForegroundColor Yellow
    Write-Host "Aktifkan manual di TV: Setelan -> Aksesibilitas -> CleanYourTV Sleep."
} else {
    Write-Host "Izin diberikan (WRITE_SECURE_SETTINGS untuk timer tidur)."
}
$grantWifi = Invoke-Adb @('shell', 'pm', 'grant', $pkg, 'android.permission.ACCESS_FINE_LOCATION')
if ($grantWifi -match 'Error|Exception') {
    Write-Host "Peringatan: izin lokasi ditolak, nama Wi-Fi tidak bisa ditampilkan (tombol tetap 'Wi-Fi')." -ForegroundColor Yellow
} else {
    Write-Host "Izin diberikan (lokasi, untuk membaca nama Wi-Fi yang tersambung)."
}

Step "Menyiapkan data CCTV di STB (tanpa kata sandi)"
# CCTV mengambil gambar langsung dari Hik-Connect, jadi tidak perlu PC yang menyala.
# Token sesi dan identitas kamera disalin sekali dari PC; launcher memperbarui tokennya sendiri.
$cctvHome = $env:CLOUDCAM_HOME
if (-not $cctvHome) { $cctvHome = Join-Path $env:USERPROFILE 'CCTV\cloudcam-home' }
$cctvFiles = @(
    @{ Local = Join-Path $cctvHome 'token.json'; Remote = 'hik_token.json' },
    @{ Local = Join-Path $cctvHome 'hik_device.json'; Remote = 'hik_device.json' }
)
$missing = @($cctvFiles | Where-Object { -not (Test-Path -LiteralPath $_.Local) })
if ($missing.Count -gt 0) {
    Write-Host "Lewati: data CCTV belum lengkap di $cctvHome" -ForegroundColor Yellow
    Write-Host "CCTV akan menampilkan 'MENUNGGU CCTV…' sampai token.json dan hik_device.json disalin." -ForegroundColor Yellow
} else {
    $uidLine = Invoke-Adb @('shell', 'stat', '-c', '%u', "/data/data/$pkg")
    $appUid = ($uidLine -split "`n")[-1].Trim()
    if ($appUid -notmatch '^\d+$') {
        Write-Host "Peringatan: UID launcher tidak terbaca; data CCTV belum disalin." -ForegroundColor Yellow
    } else {
        $copied = 0
        foreach ($file in $cctvFiles) {
            $staging = "/sdcard/$($file.Remote)"
            Invoke-Adb @('push', $file.Local, $staging) | Out-Null
            $copy = "mkdir -p /data/data/$pkg/files; cp $staging /data/data/$pkg/files/$($file.Remote); chown ${appUid}:${appUid} /data/data/$pkg/files/$($file.Remote); chmod 600 /data/data/$pkg/files/$($file.Remote); rm -f $staging"
            $result = Invoke-Adb @('shell', "su -c '$copy'")
            if ($result -match 'not found|Permission denied|denied') { break }
            $copied++
        }
        if ($copied -ne $cctvFiles.Count) {
            Write-Host "Peringatan: STB tidak mengizinkan su -c. Salin token.json dan hik_device.json manual ke /data/data/$pkg/files/" -ForegroundColor Yellow
        } else {
            Write-Host "OK    Data CCTV ada di STB; CCTV jalan tanpa PC." -ForegroundColor Green
        }
    }
}

Step "Mengaktifkan kembali suara Google (tombol mic di remote)"
foreach ($voicePkg in @('com.google.android.katniss', 'com.google.android.tts')) {
    $listed = Invoke-Adb @('shell', 'pm', 'list', 'packages', $voicePkg)
    if ($listed -notmatch "package:$([regex]::Escape($voicePkg))") {
        Write-Host "Lewati ${voicePkg}: tidak ada di TV ini." -ForegroundColor Yellow
        continue
    }
    $enable = Invoke-Adb @('shell', 'pm', 'enable', $voicePkg)
    if ($enable -match 'Error|Exception') {
        Write-Host "Peringatan: ${voicePkg} tidak bisa diaktifkan otomatis." -ForegroundColor Yellow
        Write-Host "Aktifkan manual di TV: Setelan -> Aplikasi -> lihat semua -> Google -> Aktifkan."
    } else {
        Write-Host "OK    ${voicePkg} aktif, tombol mic di remote membuka suara Google." -ForegroundColor Green
    }
}

Step "Menjadikan CleanYourTV sebagai Home"
$setHome = Invoke-Adb @('shell', 'cmd', 'package', 'set-home-activity', '--user', '0', $homeActivity)
if ($setHome) { Write-Host $setHome }
if ($setHome -match 'Unknown command|Error|Exception') {
    Write-Host "Perintah otomatis tidak didukung TV ini. Tekan tombol Home di remote, lalu pilih CleanYourTV." -ForegroundColor Yellow
} elseif ($stock) {
    Invoke-Adb @('shell', 'pm', 'disable-user', '--user', '0', $stock) | Out-Null
    Write-Host "Launcher bawaan ($stock) dinonaktifkan sementara."
}

Invoke-Adb @('shell', 'am', 'start', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME') | Out-Null

if (-not $NoReboot) {
    Step "Reboot TV"
    & adb -s $Device reboot 2>&1 | Out-Null
    Start-Sleep -Seconds 5
    Write-Host "Menunggu TV menyala lagi (biasanya 30 detik sampai 2 menit)" -NoNewline

    $booted = $false
    $deadline = (Get-Date).AddSeconds(240)
    while ((Get-Date) -lt $deadline) {
        if ($Device -match '^[\d\.]+:\d+$') { & adb connect $Device 2>&1 | Out-Null }
        $boot = (& adb -s $Device shell getprop sys.boot_completed 2>$null | Out-String).Trim()
        if ($boot -match '1') { $booted = $true; break }
        Start-Sleep -Seconds 5
        Write-Host "." -NoNewline
    }
    Write-Host ""

    if (-not $booted) {
        Write-Host "Peringatan: TV belum menjawab setelah 4 menit." -ForegroundColor Yellow
        Write-Host "Coba sambungkan lagi dengan menjalankan skrip ini tanpa -NoReboot, atau cek kabel/Wi-Fi TV." -ForegroundColor Yellow
    }

    # Tunggu launcher benar-benar terbuka, lalu beri waktu kartu sholat/bola dan grafik tergambar.
    $homeUp = $false
    $deadline = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $deadline) {
        $resumed = Invoke-Adb @('shell', 'dumpsys', 'activity', 'activities')
        if ($resumed -match "mResumedActivity.*$([regex]::Escape($pkg))") { $homeUp = $true; break }
        Start-Sleep -Seconds 3
    }
    Start-Sleep -Seconds 8

    Step "Memeriksa Home setelah reboot"
    $problems = 0

    $boot = (Invoke-Adb @('shell', 'getprop', 'sys.boot_completed')).Trim()
    if ($boot -eq '1') {
        Write-Host "OK    TV selesai boot" -ForegroundColor Green
    } else {
        Write-Host "CEK   TV belum selesai boot" -ForegroundColor Yellow
        $problems++
    }

    $resolvedHome = (Invoke-Adb @('shell', 'cmd', 'package', 'resolve-activity', '--brief', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')).Split("`n")[-1].Trim()
    if ($resolvedHome -eq $homeActivity) {
        Write-Host "OK    Home bawaan: $resolvedHome" -ForegroundColor Green
    } else {
        Write-Host "CEK   Home bawaan: $resolvedHome (seharusnya $homeActivity)" -ForegroundColor Yellow
        Write-Host "      Tekan tombol Home di remote, pilih CleanYourTV, lalu jalankan ulang skrip ini." -ForegroundColor Yellow
        $problems++
    }

    if ($homeUp) {
        Write-Host "OK    Home tampil di layar (fokus pada $pkg)" -ForegroundColor Green
    } else {
        $window = Invoke-Adb @('shell', 'dumpsys', 'window')
        if ($window -match "mCurrentFocus.*$([regex]::Escape($pkg))") {
            Write-Host "OK    Home tampil di layar (fokus pada $pkg)" -ForegroundColor Green
        } else {
            Write-Host "CEK   Layar tidak menampilkan CleanYourTV; kemungkinan ada dialog atau launcher lain." -ForegroundColor Yellow
            $problems++
        }
    }

    $versionLine = ((Invoke-Adb @('shell', 'dumpsys', 'package', $pkg)) -split "`n" |
        Where-Object { $_ -match 'versionName=' } | Select-Object -First 1)
    if ($versionLine) {
        $version = ($versionLine -replace '.*versionName=', '').Trim()
        Write-Host "OK    Versi terpasang: $version" -ForegroundColor Green
    } else {
        Write-Host "CEK   Paket $pkg tidak terbaca di TV." -ForegroundColor Yellow
        $problems++
    }

    $crash = Invoke-Adb @('shell', 'logcat', '-d', '-b', 'crash')
    if ($crash -match [regex]::Escape($pkg)) {
        Write-Host "CEK   Ada catatan crash untuk $pkg sejak boot:" -ForegroundColor Yellow
        ($crash -split "`n" | Select-Object -Last 12) | ForEach-Object { Write-Host "      $_" }
        $problems++
    } else {
        Write-Host "OK    Tidak ada crash sejak boot" -ForegroundColor Green
    }

    # Screenshot lewat /sdcard, bukan redirect, supaya berkas PNG tidak rusak di PowerShell lama.
    Invoke-Adb @('shell', 'screencap', '-p', '/sdcard/home-setelah-reboot.png') | Out-Null
    $shot = Join-Path $root 'home-setelah-reboot.png'
    & adb -s $Device pull '/sdcard/home-setelah-reboot.png' $shot 2>&1 | Out-Null
    Invoke-Adb @('shell', 'rm', '-f', '/sdcard/home-setelah-reboot.png') | Out-Null
    if ((Test-Path -LiteralPath $shot) -and ((Get-Item -LiteralPath $shot).Length -gt 50000)) {
        Write-Host "OK    Screenshot: $shot" -ForegroundColor Green
        Start-Process $shot
    } else {
        Write-Host "CEK   Screenshot gagal diambil." -ForegroundColor Yellow
        $problems++
    }

    if ($problems -eq 0) {
        Write-Host ""
        Write-Host "Semua pemeriksaan lolos: setelah reboot Home kembali normal." -ForegroundColor Green
    } else {
        Write-Host ""
        Write-Host "$problems pemeriksaan perlu dilihat. Kirim screenshot di atas kalau ada yang aneh." -ForegroundColor Yellow
    }
}

Step "Selesai"
Write-Host "CleanYourTV sudah terpasang di $Device." -ForegroundColor Green
Write-Host ""
Write-Host "Langkah berikutnya di TV:"
Write-Host "  1. Buka Setelan di kartu Home, atur lokasi sholat dan tim bola favorit."
Write-Host "  2. Coba tombol Timer di samping Setelan."
Write-Host "  3. Tekan tombol mic (ikon mikrofon) di remote untuk membuka suara Google."
Write-Host ""
Write-Host "Untuk kembali ke launcher bawaan:"
if ($stock) { Write-Host "  adb -s $Device shell pm enable $stock" }
Write-Host "  adb -s $Device shell pm uninstall $pkg"
