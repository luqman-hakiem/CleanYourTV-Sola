param(
    [Parameter(Mandatory = $true)][string]$Device,
    [string]$File = ''
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

# The adhan file: adzan.mp3 beside this script, or the original azan12.mp3.
if (-not $File) {
    foreach ($name in @('adzan.mp3', 'azan12.mp3')) {
        $candidate = Join-Path $root $name
        if (Test-Path -LiteralPath $candidate) { $File = $candidate; break }
    }
}

$pkg = 'com.gathin.tvhome'
$provider = "content://$pkg.theme/adzan.mp3"
$appDir = "/storage/emulated/0/Android/data/$pkg/files"
$remote = '/sdcard/Download/adzan.mp3'

function Fail([string]$message) {
    Write-Host ""
    Write-Host "GAGAL: $message" -ForegroundColor Red
    exit 1
}

function Step([string]$message) {
    Write-Host ""
    Write-Host "== $message" -ForegroundColor Cyan
}

function Note([string]$message) {
    Write-Host "   $message"
}

function Invoke-Adb([string[]]$Cmd) {
    $out = & adb -s $Device @Cmd 2>&1 | Out-String
    return $out.Trim()
}

# Runs one command as root on the TV: su -c "<command>".
function Invoke-Root([string]$command) {
    return Invoke-Adb @('shell', ('su -c "' + $command + '"'))
}

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    Fail "adb tidak ditemukan. Pasang Android platform-tools dan pastikan 'adb' ada di PATH."
}

if (-not $File -or -not (Test-Path -LiteralPath $File)) {
    Fail "File adzan (adzan.mp3 atau azan12.mp3) tidak ada di folder ini."
}

if ($Device -notmatch ':\d+$') { $Device = "${Device}:5555" }

Step "Menyambung ke TV $Device"
& adb connect $Device | Out-Null
$state = Invoke-Adb @('get-state')
if ($state -ne 'device') {
    Fail "TV belum tersambung ($state). Nyalakan Network/USB debugging, lalu jalankan lagi."
}
Note "Tersambung."

$model = Invoke-Adb @('shell', 'getprop ro.product.model')
$version = Invoke-Adb @('shell', 'getprop ro.build.version.release')
Note "$model, Android $version"

$installed = Invoke-Adb @('shell', "pm list packages $pkg")
if ($installed -notmatch [regex]::Escape($pkg)) {
    Fail "CleanYourTV belum terpasang di TV ini. Jalankan pasang.bat dulu, lalu ulangi."
}

if ($version -match '^\d+$' -and [int]$version -lt 8) {
    Fail "Android $version terlalu lama; launcher ini butuh Android 8.0 ke atas."
}

$localSize = (Get-Item -LiteralPath $File).Length
$localHash = (Get-FileHash -LiteralPath $File -Algorithm SHA256).Hash.ToLower()
Note "File: $([System.IO.Path]::GetFileName($File)) ($localSize B)"
Note "SHA-256: $localHash"

Step "Mengirim file ke TV"
$push = Invoke-Adb @('push', $File, $remote)
if ($push -notmatch '1 file pushed') {
    Fail "Gagal mengirim file ke TV: $push"
}
Note "Terkirim ke $remote"

# The launcher's own provider accepts adzan.mp3 under its files folder. It needs
# WRITE_SECURE_SETTINGS, which the adb shell has: no root required.
Step "Menyalin ke folder aplikasi"
$done = $false
$out = Invoke-Adb @('shell', ('content write --uri ' + $provider + ' < ' + $remote))
$readBack = Invoke-Adb @('shell', ('content read --uri ' + $provider + ' | sha256sum'))
if ($readBack -match '^([0-9a-fA-F]{64})') {
    if ($Matches[1].ToLower() -eq $localHash) {
        $done = $true
        Note "Cara: content provider (tanpa root)."
        Note "Checksum sama: $($Matches[1].ToLower())"
    }
}

if (-not $done) {
    # Fallback 1: root copy on a rooted TV.
    $suId = Invoke-Adb @('shell', 'su -c id')
    if ($suId -match 'uid=0') {
        Note "Provider tidak dipakai; mencoba salin sebagai root."
        Invoke-Root ('mkdir -p ' + $appDir) | Out-Null
        Invoke-Root ('cp ' + $remote + ' ' + $appDir + '/adzan.mp3') | Out-Null
        $owner = Invoke-Root ('stat -c %u:%g ' + $appDir)
        if ($owner -match '^\d+:\d+$') { Invoke-Root ('chown ' + $owner + ' ' + $appDir + '/adzan.mp3') | Out-Null }
        Invoke-Root ('chmod 660 ' + $appDir + '/adzan.mp3') | Out-Null
        $deviceHash = Invoke-Root ('sha256sum ' + $appDir + '/adzan.mp3')
        if ($deviceHash -match '^([0-9a-fA-F]{64})' -and $Matches[1].ToLower() -eq $localHash) {
            $done = $true
            Note "Cara: root. Checksum sama."
        }
    }
}

if (-not $done) {
    # Fallback 2: older Android lets the shell write into Android/data directly.
    Note "Mencoba menulis langsung ke folder aplikasi."
    Invoke-Adb @('shell', "mkdir -p $appDir") | Out-Null
    Invoke-Adb @('shell', "cp $remote $appDir/adzan.mp3") | Out-Null
    $deviceHash = Invoke-Adb @('shell', "sha256sum $appDir/adzan.mp3")
    if ($deviceHash -match '^([0-9a-fA-F]{64})' -and $Matches[1].ToLower() -eq $localHash) {
        $done = $true
        Note "Cara: salin langsung. Checksum sama."
    }
}

if (-not $done) {
    Write-Host ""
    Write-Host "File tidak bisa disalin otomatis ke folder aplikasi." -ForegroundColor Yellow
    Write-Host "File sudah ada di /sdcard/Download/adzan.mp3." -ForegroundColor Yellow
    Write-Host ""
    Write-Host "Pilihan:"
    Write-Host "  1. Ulangi saat launcher sudah terbuka minimal sekali (provider-nya ikut aktif)."
    Write-Host "  2. Pakai file manager di TV yang bisa membuka Android/data, lalu salin ke:"
    Write-Host "     Android/data/$pkg/files/adzan.mp3"
    Write-Host "  3. Pakai TV dengan akses root / ADB root, lalu jalankan lagi."
    Write-Host ""
    Write-Host "Tanpa file itu, pengingat adzan tetap berbunyi sebagai nada singkat." -ForegroundColor Yellow
    exit 1
}

& adb -s $Device shell "rm -f $remote" | Out-Null

Step "Selesai - satu langkah lagi di TV"
Write-Host ""
Write-Host "  File adzan.mp3 sudah ada di TV." -ForegroundColor Green
Write-Host ""
Write-Host "  Supaya berbunyi saat waktu sholat, di TV:"
Write-Host "    1. Buka Home CleanYourTV, pilih kartu Setelan."
Write-Host "    2. Cari baris 'Pengingat adzan', tekan OK sampai tertulis"
Write-Host "       'Pengingat adzan: Banner + suara adzan'."
Write-Host "    3. Di bawahnya akan muncul 'File adzan.mp3 ditemukan.'"
Write-Host "    4. Tekan 'Tes pengingat sekarang' untuk mencoba; tekan Home untuk berhenti."
Write-Host ""
Write-Host "  Kalau banner tidak muncul, izinkan 'tampil di atas aplikasi lain' untuk"
Write-Host "  CleanYourTV di Setelan Android."
Write-Host ""
