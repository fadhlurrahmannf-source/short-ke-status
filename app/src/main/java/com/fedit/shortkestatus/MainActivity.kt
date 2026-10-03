package com.fedit.shortkestatus

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var log: TextView
    private lateinit var linkInput: EditText
    private lateinit var secInput: EditText
    private lateinit var bizBox: CheckBox
    private var busy = false
    private val prefs by lazy { getSharedPreferences("cfg", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    // ------------------------------------------------------------------ UI
    private fun buildUi() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Short ke Status"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Di YouTube: Share → More → pilih \"Short ke Status WA\". Atau tempel link di bawah."
            setPadding(0, pad / 2, 0, pad)
        })
        linkInput = EditText(this).apply {
            hint = "https://youtube.com/shorts/…"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        root.addView(linkInput)
        root.addView(Button(this).apply {
            text = "Proses & kirim ke Status"
            setOnClickListener {
                val url = extractUrl(linkInput.text.toString())
                if (url == null) setStatus("Link tidak valid") else start(url)
            }
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply { text = "Maks detik per status: " })
        secInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("sec", 60).toString())
            minEms = 3
        }
        row.addView(secInput)
        root.addView(row)
        bizBox = CheckBox(this).apply {
            text = "Pakai WhatsApp Business"
            isChecked = prefs.getBoolean("biz", false)
        }
        root.addView(bizBox)

        status = TextView(this).apply {
            textSize = 18f
            setPadding(0, pad, 0, pad / 2)
            text = "Menunggu link…"
        }
        root.addView(status)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
        }
        root.addView(progress)
        log = TextView(this).apply { textSize = 12f; setPadding(0, pad / 2, 0, 0) }
        root.addView(log)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun setStatus(s: String) { status.text = s }

    private fun saveSettings(): Int {
        val sec = secInput.text.toString().toIntOrNull()?.coerceIn(10, 600) ?: 60
        prefs.edit().putInt("sec", sec).putBoolean("biz", bizBox.isChecked).apply()
        return sec
    }

    // ------------------------------------------------------------------ flow
    private fun handleIntent(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = extractUrl(text)
        if (url == null) { setStatus("Link YouTube tidak ditemukan"); return }
        linkInput.setText(url)
        start(url)
    }

    private fun extractUrl(t: String): String? = Regex("https?://\\S+").find(t)?.value

    private fun start(url: String) {
        if (busy) return
        busy = true
        val maxSec = saveSettings()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycleScope.launch {
            try {
                val parts = withContext(Dispatchers.IO) { process(url, maxSec) }
                shareToStatus(parts)
            } catch (e: Exception) {
                progress.isIndeterminate = false
                setStatus("Gagal: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    private suspend fun ui(block: () -> Unit) = withContext(Dispatchers.Main) { block() }

    private suspend fun process(url: String, maxSec: Int): List<File> {
        ui { setStatus("Menyiapkan…"); progress.isIndeterminate = true }
        Engine.init(applicationContext, prefs)

        val dir = File(cacheDir, "videos").apply { deleteRecursively(); mkdirs() }
        ui { setStatus("Mengunduh video…") }
        val req = YoutubeDLRequest(url).apply {
            // H.264 video + AAC audio so WhatsApp and the splitter can handle it
            addOption("-f", "bv*[vcodec^=avc1][height<=1080]+ba[ext=m4a]/b[ext=mp4]/b")
            addOption("--merge-output-format", "mp4")
            addOption("--no-playlist")
            addOption("-o", File(dir, "%(id)s.%(ext)s").absolutePath)
        }
        YoutubeDL.getInstance().execute(req, "job") { p, _, line ->
            runOnUiThread {
                if (p >= 0) { progress.isIndeterminate = false; progress.progress = p.toInt() }
                log.text = line
            }
        }
        val video = dir.listFiles()?.filter { it.extension.equals("mp4", true) }?.maxByOrNull { it.length() }
            ?: error("file video tidak ditemukan")

        ui { setStatus("Memotong video (maks $maxSec detik)…"); progress.isIndeterminate = true }
        return VideoSplitter.split(video, dir, maxSec)
    }

    private fun shareToStatus(parts: List<File>) {
        progress.isIndeterminate = false
        progress.progress = 100
        val uris: ArrayList<Uri> = ArrayList(parts.map { FileProvider.getUriForFile(this, "$packageName.files", it) })
        val pkg = if (bizBox.isChecked) "com.whatsapp.w4b" else "com.whatsapp"
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        intent.type = "video/mp4"
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri("video", uris[0]).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        val direct = Intent(intent).apply {
            setPackage(pkg)
            putExtra("jid", "status@broadcast") // opens WhatsApp straight on "My status"
        }
        try {
            startActivity(direct)
            setStatus("Siap! ${parts.size} video dikirim ke WhatsApp. Tinggal tekan Kirim.")
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent.createChooser(intent, "Bagikan ke"))
            setStatus("WhatsApp tidak ditemukan, pilih aplikasi secara manual.")
        }
    }
}

/** One-time setup of yt-dlp + ffmpeg, plus a daily yt-dlp update (YouTube changes often). */
object Engine {
    @Volatile private var ready = false

    fun init(ctx: Context, prefs: android.content.SharedPreferences) {
        if (!ready) {
            synchronized(this) {
                if (!ready) {
                    YoutubeDL.getInstance().init(ctx)
                    FFmpeg.getInstance().init(ctx)
                    ready = true
                }
            }
        }
        val last = prefs.getLong("lastUpdate", 0L)
        if (System.currentTimeMillis() - last > 24L * 3600 * 1000) {
            try {
                YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE)
                prefs.edit().putLong("lastUpdate", System.currentTimeMillis()).apply()
            } catch (_: Exception) {
                // offline or update failed: keep using the bundled version
            }
        }
    }
}
