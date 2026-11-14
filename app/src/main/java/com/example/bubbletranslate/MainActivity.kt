/*
 ══════════ Bubble Translate — همه کد در یک فایل ══════════

 راه‌اندازی:
 ۱) Android Studio → New Project → Empty Activity (Kotlin)، پکیج: com.example.bubbletranslate
 ۲) کل این فایل را جای MainActivity.kt بگذار (کد برنامه همین است)
 ۳) بخش MANIFEST را در AndroidManifest.xml بگذار
 ۴) بخش GRADLE را در app/build.gradle.kts اضافه کن، Sync و Run

 ──────── MANIFEST ────────
 <manifest xmlns:android="http://schemas.android.com/apk/res/android">
   <uses-permission android:name="android.permission.INTERNET"/>
   <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/>
   <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
   <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION"/>
   <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
   <application android:label="Bubble Translate"
       android:theme="@android:style/Theme.Material.Light.NoActionBar">
     <activity android:name=".MainActivity" android:exported="true">
       <intent-filter>
         <action android:name="android.intent.action.MAIN"/>
         <category android:name="android.intent.category.LAUNCHER"/>
       </intent-filter>
     </activity>
     <service android:name=".TranslateService" android:exported="false"
         android:foregroundServiceType="mediaProjection"/>
   </application>
 </manifest>

 ──────── GRADLE (app/build.gradle.kts) ────────
 android { compileSdk = 34
   defaultConfig { minSdk = 28; targetSdk = 34 }
   compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
   kotlinOptions { jvmTarget = "17" } }
 dependencies {
   implementation("com.google.mlkit:translate:17.0.3")
   implementation("com.google.mlkit:text-recognition:16.0.1")
   implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
   implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
   implementation("com.google.mlkit:text-recognition-korean:16.0.1")
   implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
   implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
   implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
 }
*/
package com.example.bubbletranslate

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.view.*
import android.widget.*
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

// ───────────── زبان‌ها ─────────────
object Langs {
    // OCR آفلاین گوگل فارسی/عربی/روسی را نمی‌خواند؛ پس مبدأ فقط این‌هاست
    val src = linkedMapOf(
        "English" to TranslateLanguage.ENGLISH, "Français" to TranslateLanguage.FRENCH,
        "Deutsch" to TranslateLanguage.GERMAN, "Español" to TranslateLanguage.SPANISH,
        "Italiano" to TranslateLanguage.ITALIAN, "Türkçe" to TranslateLanguage.TURKISH,
        "中文" to TranslateLanguage.CHINESE, "日本語" to TranslateLanguage.JAPANESE,
        "한국어" to TranslateLanguage.KOREAN, "हिन्दी" to TranslateLanguage.HINDI
    )
    val dst = linkedMapOf(
        "فارسی" to TranslateLanguage.PERSIAN, "English" to TranslateLanguage.ENGLISH,
        "العربية" to TranslateLanguage.ARABIC, "Türkçe" to TranslateLanguage.TURKISH,
        "Русский" to TranslateLanguage.RUSSIAN, "Français" to TranslateLanguage.FRENCH,
        "Deutsch" to TranslateLanguage.GERMAN, "Español" to TranslateLanguage.SPANISH
    )

    fun recognizer(l: String): TextRecognizer = when (l) {
        TranslateLanguage.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        TranslateLanguage.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        TranslateLanguage.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        TranslateLanguage.HINDI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
}

// ───────────── صفحه اصلی ─────────────
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var srcSp: Spinner
    private lateinit var dstSp: Spinner
    private lateinit var subRb: RadioButton
    private val cfg by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }
    private val srcKeys = Langs.src.keys.toList()
    private val dstKeys = Langs.dst.keys.toList()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding((20 * d).toInt(), (40 * d).toInt(), (20 * d).toInt(), (20 * d).toInt())
        }
        fun label(t: String) = root.addView(TextView(this).apply { text = t; textSize = 16f; setPadding(0, (14 * d).toInt(), 0, 4) })
        fun button(t: String, f: () -> Unit) = root.addView(Button(this).apply { text = t; setOnClickListener { f() } })

        label("زبان متن روی صفحه (مبدأ)")
        srcSp = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, srcKeys)
            setSelection(srcKeys.indexOfFirst { Langs.src[it] == cfg.getString("src", TranslateLanguage.ENGLISH) }.coerceAtLeast(0))
        }
        root.addView(srcSp)
        label("ترجمه به (مقصد)")
        dstSp = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, dstKeys)
            setSelection(dstKeys.indexOfFirst { Langs.dst[it] == cfg.getString("dst", TranslateLanguage.PERSIAN) }.coerceAtLeast(0))
        }
        root.addView(dstSp)
        label("حالت ترجمه")
        val rg = RadioGroup(this)
        val stdRb = RadioButton(this).apply { text = "استاندارد (کل صفحه)"; id = 1 }
        subRb = RadioButton(this).apply { text = "زیرنویس ویدیو (پایین صفحه، سریع‌تر)"; id = 2 }
        rg.addView(stdRb); rg.addView(subRb); root.addView(rg)
        (if (cfg.getBoolean("sub", false)) subRb else stdRb).isChecked = true

        button("۱) دانلود مدل‌ها (فقط یک بار، با اینترنت)") { save(); download() }
        button("۲) اجازه نمایش روی برنامه‌ها") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        button("۳) شروع ترجمه زنده") { start() }
        button("توقف") { stopService(Intent(this, TranslateService::class.java)) }
        status = TextView(this).apply { setPadding(0, (16 * d).toInt(), 0, 0) }
        root.addView(status)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun save() {
        cfg.edit()
            .putString("src", Langs.src[srcKeys[srcSp.selectedItemPosition]])
            .putString("dst", Langs.dst[dstKeys[dstSp.selectedItemPosition]])
            .putBoolean("sub", subRb.isChecked).apply()
    }

    private fun download() {
        val s = Langs.src[srcKeys[srcSp.selectedItemPosition]]!!
        val t = Langs.dst[dstKeys[dstSp.selectedItemPosition]]!!
        status.text = "در حال دانلود مدل‌ها…"
        val tr = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(s).setTargetLanguage(t).build())
        tr.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { status.text = "✓ دانلود شد؛ از این به بعد بدون اینترنت کار می‌کند"; tr.close() }
            .addOnFailureListener { status.text = "خطا در دانلود: ${it.message}"; tr.close() }
    }

    private fun start() {
        save()
        if (!Settings.canDrawOverlays(this)) { status.text = "اول مرحله ۲ را انجام بده"; return }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), 7)
    }

    override fun onActivityResult(rc: Int, res: Int, d: Intent?) {
        super.onActivityResult(rc, res, d)
        if (rc == 7 && res == RESULT_OK && d != null) {
            startForegroundService(Intent(this, TranslateService::class.java).putExtra("code", res).putExtra("data", d))
            moveTaskToBack(true)
        }
    }
}

// ───────────── لایه ترجمه روی صفحه ─────────────
class Item(val box: RectF, val bg: Int, val layout: StaticLayout)

class OverlayView(c: android.content.Context) : View(c) {
    @Volatile var items: List<Item> = emptyList()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    fun show(l: List<Item>) { items = l; invalidate() }

    override fun onDraw(cv: Canvas) {
        for (i in items) {
            p.color = i.bg
            cv.drawRoundRect(i.box, 8f, 8f, p)           // متن اصلی را می‌پوشاند
            cv.save()
            cv.clipRect(i.box)
            cv.translate(i.box.left, i.box.top + (i.box.height() - i.layout.height) / 2f)
            i.layout.draw(cv)                              // متن ترجمه، دقیقاً در همان جا
            cv.restore()
        }
    }
}

// حباب شناور (بدون متن تا OCR آن را نخواند)؛ آبی = خاموش، سبز = ترجمه زنده روشن
class Bubble(c: android.content.Context) : View(c) {
    var on = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) {
        val r = width / 2f
        val col = if (on) 0xFF2E7D32.toInt() else 0xFF1565C0.toInt()
        p.color = col; cv.drawCircle(r, r, r, p)
        p.color = Color.WHITE; cv.drawCircle(r, r, r * 0.5f, p)
        p.color = col; cv.drawCircle(r, r, r * 0.22f, p)
    }
}

// ───────────── سرویس اصلی: ضبط صفحه + OCR + ترجمه ─────────────
class TranslateService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var wm: WindowManager
    private lateinit var proj: MediaProjection
    private lateinit var reader: ImageReader
    private var vd: VirtualDisplay? = null
    private lateinit var overlay: OverlayView
    private lateinit var bubble: Bubble
    private lateinit var translator: Translator
    private lateinit var recognizer: TextRecognizer
    private var W = 0
    private var H = 0
    private var sub = false
    private var last: Bitmap? = null
    private var started = false
    @Volatile private var live = false
    @Volatile private var force = false
    private val cache = ConcurrentHashMap<String, String>()

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i == null) { stopSelf(); return START_NOT_STICKY }
        if (started) return START_NOT_STICKY
        started = true
        startFg()

        val cfg = getSharedPreferences("cfg", MODE_PRIVATE)
        val src = cfg.getString("src", TranslateLanguage.ENGLISH)!!
        val dst = cfg.getString("dst", TranslateLanguage.PERSIAN)!!
        sub = cfg.getBoolean("sub", false)
        recognizer = Langs.recognizer(src)
        translator = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build())
        translator.downloadModelIfNeeded().addOnFailureListener {
            Toast.makeText(this, "مدل ترجمه دانلود نشده؛ یک بار با اینترنت دانلود کن", Toast.LENGTH_LONG).show()
        }

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds; W = b.width(); H = b.height()
        } else {
            val m = android.util.DisplayMetrics(); wm.defaultDisplay.getRealMetrics(m); W = m.widthPixels; H = m.heightPixels
        }

        @Suppress("DEPRECATION") val data = i.getParcelableExtra<Intent>("data")!!
        proj = (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
            .getMediaProjection(i.getIntExtra("code", 0), data)
        proj.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, main)
        reader = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 3)
        vd = proj.createVirtualDisplay("bt", W, H, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, main)

        overlay = OverlayView(this)
        wm.addView(overlay, lp(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, false))
        addBubble()
        startLoop()
        return START_NOT_STICKY
    }

    private fun startFg() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("bt", "Bubble Translate", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "bt").setContentTitle("مترجم شناور فعال است")
            .setContentText("حباب را نگه دار تا خارج شوی").setSmallIcon(android.R.drawable.ic_menu_search).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    private fun lp(w: Int, h: Int, touchable: Boolean) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    private fun addBubble() {
        bubble = Bubble(this)
        val s = (56 * resources.displayMetrics.density).toInt()
        val bp = lp(s, s, true).apply { x = W - s - 16; y = H / 3 }
        bubble.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false; var t0 = 0L
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = bp.x; oy = bp.y; moved = false; t0 = e.eventTime }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - sx; val dy = e.rawY - sy
                        if (abs(dx) > 12 || abs(dy) > 12) moved = true
                        if (moved) { bp.x = ox + dx.toInt(); bp.y = oy + dy.toInt(); wm.updateViewLayout(v, bp) }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) { if (e.eventTime - t0 > 800) stopSelf() else toggle() }
                }
                return true
            }
        })
        wm.addView(bubble, bp)
    }

    private fun toggle() {
        live = !live
        bubble.on = live; bubble.invalidate()
        if (live) force = true else overlay.show(emptyList())
    }

    // گرفتن آخرین فریم؛ اگر صفحه تغییر نکرده باشد null برمی‌گردد (تشخیص تغییر رایگان)
    private fun grab(): Bitmap? {
        val img = reader.acquireLatestImage() ?: return null
        try {
            val p = img.planes[0]
            val stride = p.rowStride / p.pixelStride
            val raw = Bitmap.createBitmap(stride, H, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(p.buffer)
            val bmp = if (stride == W) raw else Bitmap.createBitmap(raw, 0, 0, W, H)
            last = bmp
            return bmp
        } finally { img.close() }
    }

    private fun startLoop() = scope.launch {
        while (isActive) {
            delay(if (sub) 500 else 800)
            if (!live) { grab(); continue }
            try { cycle() } catch (e: CancellationException) { throw e } catch (e: Exception) { /* دور بعد دوباره */ }
        }
    }

    private suspend fun cycle() {
        var frame = grab() ?: (if (force) last else null) ?: return
        force = false
        // لایه ترجمه را برای لحظه‌ای پاک می‌کنیم تا OCR متن اصلی را ببیند، نه ترجمه را
        if (overlay.items.isNotEmpty()) {
            withContext(Dispatchers.Main) { overlay.show(emptyList()) }
            delay(110)
            frame = grab() ?: frame
        }
        val top = if (sub) (H * 0.6f).toInt() else 0
        val crop = Bitmap.createBitmap(frame, 0, top, W, H - top)
        val res = recognizer.process(InputImage.fromBitmap(crop, 0)).await()

        val raw: List<Pair<Rect, String>> = if (sub) {
            val bs = res.textBlocks.filter { it.boundingBox != null }
            if (bs.isEmpty()) emptyList() else {
                val u = Rect(bs[0].boundingBox!!)
                bs.forEach { u.union(it.boundingBox!!) }
                listOf(u to bs.joinToString(" ") { it.text.replace("\n", " ") })
            }
        } else res.textBlocks.mapNotNull { b -> b.boundingBox?.let { it to b.text.replace("\n", " ") } }

        val out = coroutineScope {
            raw.filter { it.second.count(Char::isLetter) >= 2 }.map { (r, t) ->
                async { r to (cache[t] ?: translator.translate(t).await().also { cache[t] = it }) }
            }.awaitAll()
        }

        val items = out.map { (r, tr) ->
            val box = RectF(r).apply { offset(0f, top.toFloat()); inset(-5f, -3f) }
            val bg = sampleBg(frame, box)
            val fg = if (Color.luminance(bg) > 0.5f) Color.BLACK else Color.WHITE
            Item(box, bg, fit(tr, box, fg))
        }
        withContext(Dispatchers.Main) { overlay.show(items) }
        delay(250)
        reader.acquireLatestImage()?.close()   // فریم‌های ناشی از خود لایه را دور بریز
    }

    // رنگ پس‌زمینه را از اطراف کادر متن نمونه می‌گیرد
    private fun sampleBg(b: Bitmap, r: RectF): Int {
        var rr = 0; var g = 0; var bl = 0; var n = 0
        for (x in listOf(r.left - 4, r.right + 4)) for (y in listOf(r.top - 4, r.centerY(), r.bottom + 4)) {
            val c = b.getPixel(x.toInt().coerceIn(0, b.width - 1), y.toInt().coerceIn(0, b.height - 1))
            rr += Color.red(c); g += Color.green(c); bl += Color.blue(c); n++
        }
        return Color.rgb(rr / n, g / n, bl / n)
    }

    // بزرگ‌ترین اندازه فونتی که متن ترجمه را در کادر اصلی جا بدهد
    private fun fit(text: String, box: RectF, fg: Int): StaticLayout {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = fg }
        val w = box.width().toInt().coerceAtLeast(40)
        var size = box.height().coerceAtMost(72f)
        lateinit var l: StaticLayout
        do {
            p.textSize = size
            l = StaticLayout.Builder.obtain(text, 0, text.length, p, w)
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR).build()
            size -= 2f
        } while (l.height > box.height() && size > 10f)
        return l
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { wm.removeView(overlay) }
        runCatching { wm.removeView(bubble) }
        vd?.release()
        if (::reader.isInitialized) reader.close()
        if (::proj.isInitialized) proj.stop()
        if (::translator.isInitialized) translator.close()
        if (::recognizer.isInitialized) recognizer.close()
        super.onDestroy()
    }
}
