package com.mclaude.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * MediaProjection(시스템 화면 캡쳐 권한)으로 "현재 화면"을 떠서 갤러리에 저장한다.
 * 이 앱 안의 WebView뿐 아니라 MJ 앱·브라우저 등 어떤 화면이든 캡쳐 가능.
 * 트리거: 알림의 '📸 캡쳐' 액션 + (권한 있으면) 떠다니는 버튼.
 */
class ScreenCaptureService : Service() {

    private val ui = Handler(Looper.getMainLooper())
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    private var projection: MediaProjection? = null
    private var vDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null

    private var wm: WindowManager? = null
    private var overlay: View? = null

    private var w = 0
    private var h = 0
    private var dpi = 0
    private val lock = Any()
    private var lastBmp: Bitmap? = null
    @Volatile private var captureReq = false
    private var saved = 0
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopEverything(); return START_NOT_STICKY }
            ACTION_CAPTURE -> { captureNow(); return START_STICKY }
        }
        if (running) return START_STICKY

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? =
            if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        if (code == 0 || data == null) { stopSelf(); return START_NOT_STICKY }

        // FGS는 projection 생성 전에 떠 있어야 한다(Android 14 요구사항).
        startForegroundCompat()

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = try { mpm.getMediaProjection(code, data) } catch (_: Throwable) { null }
        if (mp == null) { stopEverything(); return START_NOT_STICKY }
        projection = mp
        // API34+: createVirtualDisplay 전에 콜백 등록 필수
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopEverything() }
        }, ui)

        val m = resources.displayMetrics
        w = m.widthPixels; h = m.heightPixels; dpi = m.densityDpi
        if (w <= 0 || h <= 0) { stopEverything(); return START_NOT_STICKY }

        bgThread = HandlerThread("mjcap").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)

        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2).also { r ->
            r.setOnImageAvailableListener({ rr ->
                val img = try { rr.acquireLatestImage() } catch (_: Throwable) { null }
                    ?: return@setOnImageAvailableListener
                try {
                    if (captureReq) {
                        val b = imageToBitmap(img)
                        if (b != null) synchronized(lock) { lastBmp?.recycle(); lastBmp = b }
                    }
                } catch (_: Throwable) {
                } finally {
                    try { img.close() } catch (_: Throwable) {}
                }
                if (captureReq) ui.post { doSave() }
            }, bgHandler)
        }

        vDisplay = try {
            mp.createVirtualDisplay(
                "mjcap", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader!!.surface, null, bgHandler
            )
        } catch (_: Throwable) { null }
        if (vDisplay == null) { stopEverything(); return START_NOT_STICKY }

        running = true
        maybeAddOverlay()
        ui.post {
            Toast.makeText(
                this,
                "전체화면 캡쳐 준비됨 · MJ 앱/브라우저를 연 뒤 알림의 ‘📸 캡쳐’(또는 떠다니는 버튼)를 누르세요",
                Toast.LENGTH_LONG
            ).show()
        }
        return START_STICKY
    }

    /** 캡쳐 요청 — 오버레이를 잠깐 숨겨 캡쳐물에 버튼이 안 들어가게 한다. */
    private fun captureNow() {
        if (!running) return
        ui.post {
            overlay?.visibility = View.GONE
            captureReq = true
            // 화면이 정지 상태라 새 프레임이 안 와도 저장되도록 안전장치
            ui.postDelayed({ if (captureReq) doSave() }, 700)
        }
    }

    private fun doSave() {
        if (!captureReq) return
        captureReq = false
        ui.postDelayed({ overlay?.visibility = View.VISIBLE }, 150)
        val handler = bgHandler ?: ui
        handler.post {
            var b: Bitmap? = synchronized(lock) {
                lastBmp?.let { try { it.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Throwable) { null } }
            }
            if (b == null) {
                // 폴백: 지금 버퍼에 있는 최신 프레임을 직접 한 번 가져온다.
                val img = try { reader?.acquireLatestImage() } catch (_: Throwable) { null }
                if (img != null) {
                    try { b = imageToBitmap(img) } catch (_: Throwable) {} finally { try { img.close() } catch (_: Throwable) {} }
                }
            }
            synchronized(lock) { lastBmp?.recycle(); lastBmp = null }
            if (b != null) saveBitmap(b!!)
            else ui.post {
                Toast.makeText(this, "캡쳐 실패 — 화면을 살짝 스크롤한 뒤 다시 ‘📸’", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveBitmap(b: Bitmap) {
        try {
            val id = "shot-" + System.currentTimeMillis()
            val fname = "$id.jpg"
            val dest = File(Storage.mediaDir(this), fname)
            FileOutputStream(dest).use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            b.recycle()
            if (dest.length() <= 0L) { dest.delete(); return }
            val items = Storage.load(this)
            // 전체화면 캡쳐는 전용 'capture' 타입 — 앱의 '캡쳐' 탭에 모아 보여준다.
            items.add(Item(id, id, "capture", "", "", fname, today()))
            Storage.save(this, items)
            try { Gallery.save(this, dest, fname, "image/jpeg") } catch (_: Throwable) {}
            saved++
            ui.post {
                Toast.makeText(this, "캡쳐 저장 · 총 ${saved}장 · 앱의 ‘캡쳐’ 탭에서 확인", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Throwable) {
            ui.post { Toast.makeText(this, "저장 실패", Toast.LENGTH_SHORT).show() }
        }
    }

    /** ImageReader 프레임(RGBA_8888, row padding 포함)을 정확한 화면 크기 비트맵으로 변환 */
    private fun imageToBitmap(img: Image): Bitmap? {
        return try {
            val plane = img.planes[0]
            val buffer = plane.buffer.also { it.rewind() }
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * w
            val bw = if (pixelStride > 0) w + rowPadding / pixelStride else w
            val tmp = Bitmap.createBitmap(if (bw > 0) bw else w, h, Bitmap.Config.ARGB_8888)
            tmp.copyPixelsFromBuffer(buffer)
            if (rowPadding == 0) tmp
            else Bitmap.createBitmap(tmp, 0, 0, w, h).also { tmp.recycle() }
        } catch (_: Throwable) {
            null
        }
    }

    /** '다른 앱 위에 표시' 권한이 있으면 떠다니는 캡쳐/중지 버튼을 띄운다(선택). */
    private fun maybeAddOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(this)
        ) return
        try {
            val wmgr = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.END
            lp.x = 16
            lp.y = 220
            val d = resources.displayMetrics.density
            fun chip(t: String, bg: Int) = TextView(this).apply {
                text = t
                textSize = 16f
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(bg)
                setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val cap = chip("📸", 0xCC7C6FF7.toInt()).apply { setOnClickListener { captureNow() } }
            val stp = chip("■", 0xCC444444.toInt()).apply { setOnClickListener { stopEverything() } }
            row.addView(cap)
            row.addView(View(this), LinearLayout.LayoutParams((8 * d).toInt(), 1))
            row.addView(stp)
            row.setOnTouchListener(object : View.OnTouchListener {
                var ix = 0; var iy = 0; var dx = 0f; var dy = 0f; var moved = false
                override fun onTouch(v: View, e: MotionEvent): Boolean {
                    when (e.action) {
                        MotionEvent.ACTION_DOWN -> {
                            ix = lp.x; iy = lp.y; dx = e.rawX; dy = e.rawY; moved = false; return false
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val mx = (e.rawX - dx).toInt(); val my = (e.rawY - dy).toInt()
                            if (abs(mx) > 8 || abs(my) > 8) moved = true
                            lp.x = ix - mx; lp.y = iy + my
                            try { wmgr.updateViewLayout(row, lp) } catch (_: Throwable) {}
                            return moved
                        }
                    }
                    return false
                }
            })
            wmgr.addView(row, lp)
            wm = wmgr
            overlay = row
        } catch (_: Throwable) {
        }
    }

    private fun startForegroundCompat() {
        val chId = "mjcap"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(chId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(chId, "화면 캡쳐", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        fun pi(action: String): PendingIntent {
            val i = Intent(this, ScreenCaptureService::class.java).setAction(action)
            val fl = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            return PendingIntent.getService(this, action.hashCode(), i, fl)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, chId)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        val notif = builder
            .setContentTitle("MJ 화면 캡쳐 실행 중")
            .setContentText("‘📸 캡쳐’로 지금 화면을 저장하세요")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_camera, "📸 캡쳐", pi(ACTION_CAPTURE))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "■ 중지", pi(ACTION_STOP))
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun stopEverything() {
        running = false
        captureReq = false
        try { overlay?.let { wm?.removeView(it) } } catch (_: Throwable) {}
        overlay = null
        try { vDisplay?.release() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
        vDisplay = null; reader = null; projection = null
        synchronized(lock) { lastBmp?.recycle(); lastBmp = null }
        try { bgThread?.quitSafely() } catch (_: Throwable) {}
        bgThread = null; bgHandler = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_CAPTURE = "com.mclaude.app.CAPTURE"
        const val ACTION_STOP = "com.mclaude.app.STOP"
        private const val NOTIF_ID = 42
    }
}
