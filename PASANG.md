# Cara pasang CleanYourTV Sola (build khusus)

Launcher Android TV / Google TV bergaya Apple TV, berbasis [CleanYourTV](https://github.com/Gathin23/cleanyourtv) karya Gathin, dengan tambahan:

- Kartu **jadwal sholat** (lokasi dipilih dari kode pos, dihitung langsung di TV)
- **Pengingat adzan**: banner di atas aplikasi apa pun, bisa dengan suara adzan
- **Jadwal bola** tim favorit (FotMob atau Gemini), lengkap dengan **skor dan menit berjalan** saat pertandingan berlangsung
- **Timer tidur** di samping tombol Setelan (TV mati sendiri, ada banner peringatan 1 menit sebelum mati). Kalau layanan pembantu belum aktif saat waktunya habis, timer mencoba ulang otomatis tiap 15 detik sampai 6 menit, jadi tidak gagal diam-diam
- Kartu jadwal sholat dan jadwal bola dibuat **lebih besar**; kartu kalender disembunyikan secara bawaan (bisa dimunculkan lagi di Setelan)
- Kartu bergaya **kaca bening (tembus pandang)**: wallpaper tetap terlihat di balik latar buram, dengan kilau tipis di atas, tepi terang, dan teks putih tebal yang mudah dibaca dari sofa
- **Cuaca hari ini** tepat di bawah tanggal, dengan ikon yang berubah mengikuti kondisi (mis. `Gerimis · 28°C · 26°–29°`), mengikuti lokasi jadwal sholat. Saat jam berjalan berisi hujan (minimal 0,1 mm), teks dan ikon mengikuti data per jam, jadi gerimis tidak lagi tertulis "Berawan"
- **Baris statistik** kecil di bawah jam: CPU, RAM dan penyimpanan yang sedang dipakai (build 3.27-3.38), dengan ikon dan teks yang disejajarkan
- **Dock tanpa nama**: hanya ikon aplikasi, tanpa nama besar di atas dock (posisi dock tidak bergeser)
- **Kecepatan internet** kecil berhuruf tebal di kiri nama Wi-Fi: unduh dan unggah dalam Mbps, diperbarui tiap detik selama Home tampil. Pilnya bening seperti pil lain saat diam; latarnya baru menjadi gelap tembus pandang selama uji kecepatan berjalan, supaya angkanya terbaca di wallpaper terang. Tekan OK di pil itu untuk **uji kecepatan** (Ping, Unduh, Unggah) lewat server publik Cloudflare; hasilnya tertinggal di pil 10 detik (build 3.36-3.41)
- **Menu Jaringan** saat tombol Wi-Fi ditekan lama: Wi-Fi dan **Bluetooth**. Baris Bluetooth membuka layar "Tambahkan aksesori" bawaan TV, karena Setelan TV SDMC/AX810 tidak punya layar Bluetooth sendiri (build 3.39)
- **Tombol mic di remote** membuka suara Google (build 3.35). Di TV SDMC/AX810 tombol itu dulu tidak bereaksi karena aplikasi suara Google-nya dimatikan; build ini menyalakannya lagi dan menambahkan jembatan kecil di launcher

Butuh TV Android 8 ke atas, dan sebuah HP Android (pakai Termux) atau komputer untuk menjalankan adb. Cukup sekali saat memasang.

> **Penting soal tanda tangan aplikasi.** APK di paket ini memakai kunci debug, jadi berbeda dari APK resmi di halaman Releases repo. Kalau di TV sudah ada CleanYourTV versi resmi, aplikasinya **harus dihapus dulu** (langkah 4A) — tidak bisa ditimpa langsung. Data launcher lama ikut terhapus; data aplikasi lain tidak tersentuh.

> Launcher ini menggantikan Home bawaan TV. Ikuti langkahnya pelan-pelan, dan simpan nama launcher bawaanmu (langkah 3) supaya bisa dikembalikan kapan saja.

## 1. Nyalakan ADB di TV

1. Buka **Settings → Device Preferences → About**, tekan **Build** 7 kali sampai muncul "You are now a developer".
2. Kembali ke **Device Preferences → Developer options**, nyalakan **USB debugging** (di sebagian TV ada juga **Network debugging** / **ADB over network**, nyalakan juga).
3. Catat IP TV di **Settings → Network & Internet →** nama Wi-Fi kamu.

HP/komputer dan TV harus tersambung ke Wi-Fi yang sama.

## 2. Siapkan adb

**Pakai HP (Termux).** Pasang Termux dari F-Droid (versi Play Store sudah lama tidak diperbarui), lalu:

```
pkg install android-tools
termux-setup-storage
```

Izinkan akses penyimpanan saat diminta. File yang kamu unduh ada di `~/storage/downloads/`.

**Pakai komputer.** Pasang [Android platform-tools](https://developer.android.com/tools/releases/platform-tools), lalu buka Terminal / Command Prompt di folder tempat APK diunduh.

Unduh/salin `CleanYourTV-Sola-3.54-cctv-aligned.apk` (ada di folder yang sama dengan panduan ini) ke HP atau komputer.

## 3. Sambungkan dan cari launcher bawaan

```
adb connect <ip-tv>:5555
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
```

Saat pertama kali, di layar TV muncul pertanyaan izin debugging: centang **Always allow**, lalu **OK**.

Baris terakhir hasil perintah kedua adalah launcher yang sedang jadi Home, misalnya `com.google.android.tvlauncher/...`, `com.google.android.apps.tv.launcherx/...` (Google TV), atau `com.mitv.tvhome/...` (Xiaomi). **Catat nama paketnya** (bagian sebelum `/`); di langkah berikutnya ini disebut `<launcher-bawaan>`.

Kalau hasilnya `com.gathin.tvhome/...`, berarti TV-mu sudah memakai CleanYourTV, dan launcher bawaannya sedang dimatikan. Cari dengan:

```
adb shell pm list packages -d
```

Daftar itu berisi aplikasi yang sedang dimatikan; launcher bawaanmu biasanya salah satu dari tiga nama di atas.

## 4. Pasang

Pilih salah satu.

> Kalau memakai Windows dan folder ini disalin utuh, `pasang.bat` mengerjakan pasang, jadikan Home, izin, reboot, sekaligus memeriksa hasilnya. Panduan di bawah untuk pemasangan manual.

### A. Pasang baru

Untuk **semua pengguna baru**, baik TV-nya belum pernah memakai CleanYourTV maupun sudah memakai CleanYourTV versi lain (versi asli Gathin atau build sendiri). Versi lain itu tidak bisa ditimpa langsung karena tanda tangan aplikasinya berbeda, jadi dihapus dulu.

Kalau sebelumnya sudah ada CleanYourTV, **pengaturan launcher lamanya ikut terhapus** (tema, wallpaper, urutan aplikasi, file yang pernah di-push). Aplikasi lain di TV tidak tersentuh.

Di Termux (ganti path-nya kalau pakai komputer), jalankan berurutan:

```
adb shell pm enable <launcher-bawaan>
adb uninstall com.gathin.tvhome
adb install ~/storage/downloads/CleanYourTV-Sola-3.54-cctv-aligned.apk
adb shell cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity
adb shell pm disable-user --user 0 <launcher-bawaan>
```

Urutannya sengaja begitu supaya TV tidak sempat kehilangan Home: launcher bawaan dihidupkan dulu, baru launcher lama dihapus.

Kalau TV-mu belum pernah memakai CleanYourTV, baris kedua menjawab `Failure` atau `Unknown package`. Itu normal, lanjutkan saja.

Tekan tombol **Home** di remote untuk mengecek, lalu lanjut ke langkah 5.

### B. Update dari build khusus ini

Kalau TV-mu sudah memakai build khusus ini (kunci debug yang sama) dan cuma ingin memasang versi baru, semua pengaturan tetap aman:

```
adb install -r ~/storage/downloads/CleanYourTV-Sola-3.54-cctv-aligned.apk
```

Selesai, langkah 5 dan 6 tidak perlu diulang. Kalau gagal dengan pesan `INSTALL_FAILED_UPDATE_INCOMPATIBLE` atau `signatures do not match`, berarti yang terpasang adalah CleanYourTV versi resmi (kunci berbeda); pakai **A. Pasang baru**.

## 5. Beri izin (adzan dan timer tidur)

```
adb shell appops set com.gathin.tvhome SYSTEM_ALERT_WINDOW allow
adb shell appops set com.gathin.tvhome SCHEDULE_EXACT_ALARM allow
adb shell appops set com.gathin.tvhome APP_AUTO_START allow
adb shell pm grant com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS
adb shell pm enable com.google.android.katniss
adb shell pm enable com.google.android.tts
```

Tiga yang pertama supaya banner adzan bisa tampil di atas aplikasi lain, tepat waktu, dan launcher ikut menyala setelah TV restart (di sebagian TV perintah `APP_AUTO_START` tidak dikenal; abaikan saja kalau error).

Dua yang terakhir menyalakan kembali **suara Google** (`katniss`) dan mesin bacaannya (`tts`). Di sebagian TV SDMC/AX810 keduanya dimatikan, jadi tombol mic di remote tidak bereaksi; setelah diaktifkan, tombol itu membuka layar voice search. Kalau perintahnya error karena paketnya memang tidak ada di TV, abaikan saja.

Yang terakhir untuk **timer tidur**: dengan izin ini launcher bisa menyalakan sendiri layanan aksesibilitasnya, sehingga TV benar-benar mati saat timer habis tanpa perlu mengutak-atik Setelan TV. Tanpa izin ini timer tetap berjalan dan bannernya tetap muncul, tetapi TV tidak akan terkunci sendiri (bisa diaktifkan manual di **Setelan → Aksesibilitas**).

## 6. Suara adzan (opsional)

Paket ini sudah membawa `adzan.mp3` (3 menit 20 detik). Cara termudah: klik 2x **`pasang-adzan.bat`**, masukkan IP TV. Skripnya mengirim file ke folder launcher lewat content provider bawaan launcher, jadi **tidak perlu root**, lalu memeriksa checksum-nya.

Kalau mau suara lain, timpa `adzan.mp3` di folder ini lalu jalankan `pasang-adzan.bat` lagi. Atau kerjakan manual:

```
adb push adzan.mp3 /sdcard/Download/adzan.mp3
adb shell 'content write --uri content://com.gathin.tvhome.theme/adzan.mp3 < /sdcard/Download/adzan.mp3'
```

Mau adzan khusus Subuh? Ulangi dengan nama `adzan_subuh.mp3`. Kalau tidak ada, Subuh memakai `adzan.mp3`.

Terakhir, di TV: buka **Setelan → Pengingat adzan**, tekan baris itu sampai tertulis **Banner + suara adzan**, lalu pastikan di bawahnya muncul **File adzan.mp3 ditemukan.** Tekan **Tes pengingat sekarang** untuk mencoba; tekan **Home** untuk berhenti.

Supaya ringan, kecilkan dulu file-nya (misalnya jadi mono 48 kbps) — file bawaan paket ini sekitar 1 MB.

## 7. Atur di TV

Buka **Launcher settings** dari Home:

- **Widget Home**: gaya kartu, ketebalan latar, tampil/sembunyi tiap kartu, urutan kartu, dan **Lebarkan kartu**. Kalau Lebarkan kartu dimatikan, tiap kartu tetap berukuran normal dan bisa ditaruh di kiri, tengah, atau kanan (rapi kalau cuma satu atau dua kartu yang tampil).
- **Jadwal sholat & adzan**: lokasi, mode pengingat (banner / nada / suara adzan), waktu sholat mana yang diingatkan, dan tombol **Tes pengingat sekarang**.
- **Jadwal bola**: tim favorit dan sumber jadwal. FotMob langsung jalan; Gemini butuh API key gratis dari aistudio.google.com/apikey (petunjuknya muncul di layar). Mengetik key paling enak lewat aplikasi Google TV di HP. Saat timmu bertanding, baris jadwal menampilkan **skor dan menit berjalan** (contoh `● 67'  2 - 1` warna merah), dan skor akhir berwarna hijau setelah selesai; otomatis diperbarui tiap 5 menit selama laga berjalan.

Pengingat adzan hanya bunyi saat TV menyala. Tekan **Home** untuk menghentikan adzan yang sedang diputar.

## Timer tidur

Tombol **Timer** ada di sebelah **Setelan** di Home. Pilih durasinya; tombol menampilkan sisa waktu (mis. `Timer · 15 mnt`). Satu menit sebelum habis muncul banner peringatan, lalu TV tidur sendiri. Tekan Timer lagi untuk membatalkan atau mengganti durasi.

- Perlu **Android 9 ke atas** untuk benar-benar mematikan layar. Di Android 8 timer dan bannernya tetap jalan, tetapi layar tidak terkunci otomatis.
- Butuh izin `WRITE_SECURE_SETTINGS` dari langkah 5. Tanpa itu, aktifkan layanan **CleanYourTV Sleep** secara manual di **Setelan → Aksesibilitas**.
- Setelah TV tidur, tekan tombol power/remote seperti biasa untuk menyalakannya lagi.

## Kembali ke launcher bawaan

```
adb shell pm enable <launcher-bawaan>
adb shell pm uninstall com.gathin.tvhome
```

## Catatan

- **Xiaomi Mi TV baru** (sebagian model) hanya mengizinkan launcher tertentu jadi Home: perintah di langkah 4 terlihat berhasil, tapi tombol Home tetap membuka launcher Xiaomi. Kalau itu terjadi, jalankan perintah "Kembali ke launcher bawaan" di atas. Mi TV 4A sudah dicoba dan aman.
- Data jadwal bola dari FotMob memakai API tidak resmi, jadi suatu saat bisa berhenti. Pindah ke Gemini di Launcher settings kalau itu terjadi.
- Cuaca diambil dari Open-Meteo memakai koordinat lokasi sholat; diperbarui tiap 20 menit dan tetap menampilkan bacaan terakhir saat TV offline. Kalau lokasi sholat diganti, cuaca ikut pindah.
- Key Gemini, lokasi sholat, dan tim favorit disimpan di TV-mu sendiri, tidak dikirim ke mana pun selain ke layanan yang kamu pilih (FotMob/Gemini dan Open-Meteo).

## Lisensi

MIT, sama seperti CleanYourTV. Terima kasih untuk [Gathin](https://github.com/Gathin23/cleanyourtv) atas launcher aslinya. Font Outfit memakai SIL Open Font License.
