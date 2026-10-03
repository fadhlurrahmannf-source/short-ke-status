# Short ke Status

Aplikasi Android: share link YouTube Shorts → video diunduh jadi MP4 → kalau lebih panjang dari batas status WhatsApp, dipotong otomatis → semua potongan langsung dibuka di "Status saya" WhatsApp. Tinggal tekan **Kirim**.

## Cara bikin APK (tanpa install apa-apa, lewat GitHub)

1. Buat akun di github.com (gratis), lalu buat repository baru (misalnya `short-ke-status`).
2. Klik **"uploading an existing file"**, lalu upload **semua isi folder ini** (termasuk folder `.github`). Klik **Commit changes**.
3. Buka tab **Actions** di repository. Build jalan otomatis (sekitar 5–10 menit).
4. Kalau sudah centang hijau, klik build-nya → di bagian **Artifacts** download `ShortKeStatus-apk` (file .zip berisi APK).
5. Ekstrak zip-nya di HP, buka file `app-debug.apk`, izinkan "Instal dari sumber tidak dikenal".

Alternatif: buka folder ini di **Android Studio** di laptop, lalu Build → Build APK.

## Cara pakai

1. Tonton Shorts di YouTube → **Share** → **More/Lainnya** → pilih **Short ke Status WA**.
2. Tunggu proses download + potong.
3. WhatsApp terbuka di halaman Status dengan semua video → tekan **Kirim**.

Pengaturan di aplikasi:
- **Maks detik per status** (default 60). Ubah kalau batas WhatsApp di HP-mu berbeda.
- **Pakai WhatsApp Business** kalau kamu pakai WA Business.

## Catatan

- Saat pertama dibuka / sekali sehari, aplikasi meng-update mesin pengunduhnya (yt-dlp) supaya tetap bisa dipakai saat YouTube berubah.
- Pemotongan tanpa render ulang (cepat, kualitas tidak turun), dipotong di keyframe, jadi tiap potongan bisa sedikit lebih pendek dari batas.
