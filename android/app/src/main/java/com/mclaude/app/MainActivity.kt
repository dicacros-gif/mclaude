package com.mclaude.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.tabs.TabLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var galleryArea: View
    private lateinit var grid: RecyclerView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var empty: TextView
    private lateinit var btnLogin: Button
    private lateinit var btnCrawl: Button
    private lateinit var tabsView: TabLayout
    private lateinit var webTabs: TabLayout
    private lateinit var dateSpinner: Spinner
    private lateinit var btnOptions: Button
    private lateinit var btnGallery: Button
    private lateinit var btnCapture: Button
    private lateinit var btnDownload: Button
    private lateinit var adapter: GalleryAdapter

    // 다중선택 삭제 액션 바
    private lateinit var selectionBar: View
    private lateinit var selCount: TextView

    private val handler = Handler(Looper.getMainLooper())

    // 저장된 전체 항목(메모리 캐시) + 현재 필터
    private var allItems: MutableList<Item> = mutableListOf()
    private var currentType = "image"
    private var currentDate = ALL
    private var dateOptions: List<String> = listOf(ALL)
    private var settingSpinner = false

    // 크롤 상태
    private var crawling = false
    private var tabIndex = 0
    private var tabResultReceived = false
    private var emptyRetries = 0
    private var watchdog: Runnable? = null
    private val collected = LinkedHashMap<String, Triple<String, String, String>>() // jobId -> (url, link, type)

    // 옵션(SharedPreferences 저장)
    private lateinit var prefs: SharedPreferences
    private var method = METHOD_AUTO                 // auto | direct | capture
    private var enabledTypes = linkedSetOf("image", "style", "video")
    private var autoStart = false
    private var saveToAlbum = true
    private var scrollCapture = true
    private var activeTabs: List<Pair<String, String>> = emptyList()

    // 탐색(웹뷰) 모드: 현재 화면 이미지 URL 다운로드 / 빈 화면(차단) 감지 상태
    private var manualDownload = false               // '⬇ 다운' 1회성 URL 다운로드
    private var blankProbeTries = 0                  // 빈 화면 재확인 횟수
    private var blankDialogShown = false             // 대안 다이얼로그 중복 방지

    // 단계/캡쳐 상태
    private var stage = STAGE_DIRECT
    private var manualCapture = false                // 사용자가 직접 '📸 저장' 누른 1회성 캡쳐
    private var capIndex = 0
    private var captureStarted = false
    private var captureStep = 0
    private var captureSavedTotal = 0
    private var capType = "image"
    private var captureItems: MutableList<Item> = mutableListOf()
    private var captureHave: HashSet<String> = HashSet()
    private var captureWatch: Runnable? = null

    // 크롤할 사이트 탭: (탭 파라미터, 항목 type)
    private val siteTabs = listOf(
        "top" to "image",
        "styles_top" to "style",
        "video_top" to "video"
    )
    private val tabLabel =
        mapOf("image" to "이미지", "style" to "스타일", "video" to "비디오", "capture" to "캡쳐")
    private val baseUrl = "https://www.midjourney.com/explore?tab="

    // 탐색(웹뷰) 모드 탭 ↔ 공개 explore URL 파라미터 / 표시 라벨
    //  0:이미지=top, 1:스타일=styles_top, 2:비디오=video_top
    private val webTabParams = listOf("top", "styles_top", "video_top")
    private val webTabLabels = listOf("이미지", "스타일", "비디오")

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        web = findViewById(R.id.web)
        galleryArea = findViewById(R.id.galleryArea)
        grid = findViewById(R.id.grid)
        swipe = findViewById(R.id.swipe)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        empty = findViewById(R.id.empty)
        btnLogin = findViewById(R.id.btnLogin)
        btnCrawl = findViewById(R.id.btnCrawl)
        tabsView = findViewById(R.id.tabs)
        webTabs = findViewById(R.id.webTabs)
        dateSpinner = findViewById(R.id.dateSpinner)
        btnOptions = findViewById(R.id.btnOptions)
        btnGallery = findViewById(R.id.btnGallery)
        btnCapture = findViewById(R.id.btnCapture)
        btnDownload = findViewById(R.id.btnDownload)
        selectionBar = findViewById(R.id.selectionBar)
        selCount = findViewById(R.id.selCount)

        prefs = getSharedPreferences("mj_prefs", MODE_PRIVATE)
        loadOptions()

        // --- 갤러리 ---
        grid.layoutManager = GridLayoutManager(this, 3)
        adapter = GalleryAdapter(this, mutableListOf(), ::onDeleteItem, ::onOpenItem, ::onSelectionChanged)
        grid.adapter = adapter
        allItems = Storage.load(this)

        // 다중선택 액션 바 버튼
        findViewById<Button>(R.id.selCancel).setOnClickListener { adapter.exitSelection() }
        findViewById<Button>(R.id.selAll).setOnClickListener { adapter.selectAll() }
        findViewById<Button>(R.id.selDelete).setOnClickListener { bulkDelete() }

        // 카테고리 탭(항상 보임)
        tabsView.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                adapter.exitSelection()          // 카테고리 바꾸면 선택 해제
                currentType = when (tab.position) {
                    1 -> "style"
                    2 -> "video"
                    3 -> "capture"
                    else -> "image"
                }
                applyFilter()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        // 탐색(웹뷰) 모드 탭 — 누르면 해당 공개 explore URL 로드 (이미지/스타일/비디오)
        webTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (!crawling) loadExploreTab(tab.position)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {
                if (!crawling) loadExploreTab(tab.position)   // 같은 탭 다시 눌러도 재로딩
            }
        })

        // 날짜 드롭다운
        dateSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (settingSpinner) return
                adapter.exitSelection()          // 날짜 바꾸면 선택 해제
                currentDate = dateOptions.getOrElse(pos) { ALL }
                applyFilter()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        rebuildDates()
        applyFilter()

        // --- WebView ---
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.databaseEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = true
        // 실제 브라우저처럼 이미지를 로드해야 currentSrc/지연로딩 썸네일이 채워져 추출이 안정적이다.
        web.settings.loadsImagesAutomatically = true
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = true
        // 기본 WebView UA는 "wv" 토큰 + 오래된 Chrome 버전이라 MJ가 다른(깨진) 화면을 준다.
        // 일반 모바일 Chrome UA로 맞춰야 웹사이트/앱과 똑같이 이미지가 렌더된다.
        // ※ 버전이 너무 낡으면(예전 Chrome 136) MJ/Cloudflare가 빈/차단 화면을 주므로
        //   최신 안정판 Chrome으로 맞춘다(미갱신 시 '예전엔 보였는데 안 보임' 증상의 원인).
        web.settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/148.0.0.0 Mobile Safari/537.36"
        web.addJavascriptInterface(JsBridge(), "AndroidCrawler")
        // 일부 사이트는 WebChromeClient(진행률/콘솔/타이틀 콜백)가 있어야 정상 렌더된다.
        web.webChromeClient = android.webkit.WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (crawling) {
                    if (stage == STAGE_CAPTURE) {
                        // 캡쳐 단계: 페이지가 뜨면 스크롤+스크린샷 루프 시작
                        if (!captureStarted) web.postDelayed({ startCaptureScroll() }, 2200)
                    } else if (!tabResultReceived) {
                        // CF 챌린지가 리다이렉트 후 실제 페이지를 다시 로드할 수 있으므로
                        // 결과를 받기 전이면 페이지가 끝날 때마다 재주입한다.
                        web.postDelayed({ injectExtract() }, 1200)
                    }
                    return
                }
                // 탐색 모드(수동): 잠시 후 MJ 이미지 수를 세어 빈/차단 화면이면 대안을 제시
                if (web.visibility == View.VISIBLE) {
                    web.postDelayed({ probeBlank() }, 2600)
                }
            }
        }

        btnLogin.setOnClickListener { openLogin() }
        btnCrawl.setOnClickListener { startCrawl() }
        btnOptions.setOnClickListener { showOptionsDialog() }
        btnCapture.setOnClickListener { captureCurrentScreen() }
        btnDownload.setOnClickListener { downloadCurrentPage() }
        btnGallery.setOnClickListener { backToGallery() }
        swipe.setOnRefreshListener {
            swipe.isRefreshing = false
            startCrawl()
        }

        // Android 9 이하에서는 공용 Pictures 폴더(갤러리) 저장에 쓰기 권한 필요
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1001
            )
        }

        // 메인 화면은 '갤러리'다 — 앱을 켜면 저장된 이미지부터 보여준다.
        showGalleryMode()
        selectFirstNonEmptyTab()              // 저장분이 다른 카테고리면 그 탭을 자동 선택
        if (allItems.isEmpty())
            setStatus("저장된 이미지가 없습니다 · ‘🔍 탐색’ → 탭(이미지/스타일/비디오) 전환 → ‘⬇ 다운’/‘📸 저장’, 또는 ↻ 크롤")
        else
            setStatus("총 ${allItems.size}장 · ↻ 크롤/‘📸 저장’으로 추가 · 사진을 누르면 확대")
        // 자동 크롤은 옵션(기본 꺼짐)일 때만 — 갤러리를 먼저 보여준 뒤 시작
        if (autoStart) handler.postDelayed({ startCrawl() }, 800)
    }

    // ----------------------------------------------------------------
    //  탐색 — MJ 공개 explore 페이지를 연다. 로그인 없이도 공개 이미지가 보인다.
    //  (원하면 이 화면에서 직접 로그인도 가능 / CF는 사용자 IP·세션으로 통과)
    // ----------------------------------------------------------------
    private fun openLogin() {
        crawling = false
        manualCapture = false
        manualDownload = false
        cancelWatchdog()
        cancelCaptureWatch()
        progress.visibility = View.GONE
        setStatus("로그인 없이 둘러보기 · 탭으로 전환 · 사진 보이면 ‘⬇ 다운’/‘📸 저장’ · 끝나면 ‘🖼 갤러리’")
        showWebMode()
        selectWebTab(0)            // 이미지(top) 탭부터
    }

    /** 탐색 탭을 선택(하이라이트)하고 해당 explore URL을 로드 — 중복 로드 없이 한 번만 */
    private fun selectWebTab(pos: Int) {
        if (webTabs.selectedTabPosition == pos) {
            loadExploreTab(pos)               // 이미 선택된 탭 → 직접 로드(리스너 안 불림)
        } else {
            webTabs.getTabAt(pos)?.select()   // 선택 변경 → onTabSelected → loadExploreTab
        }
    }

    /** 탭 위치(0:이미지/1:스타일/2:비디오)에 해당하는 공개 explore 페이지를 웹뷰에 로드 */
    private fun loadExploreTab(pos: Int) {
        val p = pos.coerceIn(0, webTabParams.size - 1)
        blankProbeTries = 0
        blankDialogShown = false
        manualDownload = false
        setStatus("불러오는 중: ${webTabLabels[p]} 탭… (로그인 불필요)")
        web.loadUrl(baseUrl + webTabParams[p])
    }

    /** 웹뷰(로그인/탐색) 화면으로 전환 — 상단 버튼을 그 모드에 맞게 토글 */
    private fun showWebMode() {
        galleryArea.visibility = View.GONE
        web.visibility = View.VISIBLE
        webTabs.visibility = View.VISIBLE
        btnGallery.visibility = View.VISIBLE
        btnDownload.visibility = View.VISIBLE
        btnCapture.visibility = View.VISIBLE
        btnLogin.visibility = View.GONE
        btnOptions.visibility = View.GONE
    }

    /** 갤러리(저장된 이미지 보기) 화면으로 전환 — 메인 화면 */
    private fun showGalleryMode() {
        web.visibility = View.GONE
        webTabs.visibility = View.GONE
        galleryArea.visibility = View.VISIBLE
        btnGallery.visibility = View.GONE
        btnDownload.visibility = View.GONE
        btnCapture.visibility = View.GONE
        btnLogin.visibility = View.VISIBLE
        btnOptions.visibility = View.VISIBLE
    }

    /** 웹뷰에서 갤러리로 돌아오기 — 방금 저장한 것까지 새로고침해서 보여준다 */
    private fun backToGallery() {
        manualCapture = false
        allItems = Storage.load(this)
        showGalleryMode()
        rebuildDates()
        applyFilter()
        selectFirstNonEmptyTab()
        setStatus(
            if (allItems.isEmpty()) "저장된 이미지가 없습니다 · ‘🔍 탐색’ → 탭 전환 → ‘⬇ 다운’/‘📸 저장’"
            else "총 ${allItems.size}장 · 사진을 누르면 확대"
        )
    }

    /** 현재 카테고리 탭이 비어 있고 다른 탭에 항목이 있으면 그 탭을 자동 선택 */
    private fun selectFirstNonEmptyTab() {
        if (allItems.any { it.type == currentType }) return
        val order = listOf("image" to 0, "style" to 1, "video" to 2, "capture" to 3)
        for ((t, idx) in order) {
            if (allItems.any { it.type == t }) {
                tabsView.getTabAt(idx)?.select()   // 리스너가 currentType/applyFilter 갱신
                return
            }
        }
    }

    // ----------------------------------------------------------------
    //  외부 앱/브라우저로 MJ 열기 + 전체화면 캡쳐(MediaProjection)
    //  이 앱 WebView에서 안 보일 때의 대안: MJ 앱·브라우저에서 보고 화면을 캡쳐
    // ----------------------------------------------------------------
    private fun openMjApp() {
        val url = "https://www.midjourney.com/explore"
        // 1) MJ 앱 직접 실행 시도(후보 패키지)
        val pkgs = listOf(
            "com.midjourney.app", "com.midjourney.Midjourney",
            "com.midjourney.midjourney", "com.midjourney"
        )
        for (p in pkgs) {
            val li = packageManager.getLaunchIntentForPackage(p)
            if (li != null) {
                li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { startActivity(li); return } catch (_: Exception) {}
            }
        }
        // 2) midjourney.com 링크 핸들러로 열기 — 앱 링크면 MJ 앱, 아니면 브라우저
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            Toast.makeText(this, "MJ를 열 앱이 없습니다", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openInBrowser() {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.midjourney.com/explore"))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            Toast.makeText(this, "브라우저가 없습니다", Toast.LENGTH_SHORT).show()
        }
    }

    /** 시스템 화면 캡쳐 권한을 요청 → 허용되면 onActivityResult에서 서비스 시작 */
    private fun startScreenCapture() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS")
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf("android.permission.POST_NOTIFICATIONS"), 1002
            )
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        if (mpm == null) {
            Toast.makeText(this, "이 기기는 화면 캡쳐를 지원하지 않습니다", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
        } catch (_: Exception) {
            Toast.makeText(this, "화면 캡쳐를 시작할 수 없습니다", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopScreenCapture() {
        try {
            startService(
                Intent(this, ScreenCaptureService::class.java)
                    .setAction(ScreenCaptureService.ACTION_STOP)
            )
        } catch (_: Exception) {}
        Toast.makeText(this, "전체화면 캡쳐 중지", Toast.LENGTH_SHORT).show()
    }

    /** (선택) 떠다니는 캡쳐 버튼을 쓰려면 '다른 앱 위에 표시' 권한 화면을 연다 */
    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) } catch (_: Exception) {}
            }
        } else {
            Toast.makeText(this, "이 버전은 떠다니는 버튼이 항상 가능합니다", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                val svc = Intent(this, ScreenCaptureService::class.java)
                    .putExtra(ScreenCaptureService.EXTRA_CODE, resultCode)
                    .putExtra(ScreenCaptureService.EXTRA_DATA, data)
                try {
                    ContextCompat.startForegroundService(this, svc)
                    Toast.makeText(
                        this,
                        "캡쳐 시작됨 · MJ 앱/브라우저를 열고 알림의 ‘📸 캡쳐’로 저장 → ‘🖼 갤러리’ 확인",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (_: Exception) {
                    Toast.makeText(this, "캡쳐 서비스를 시작하지 못했습니다", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "화면 캡쳐 권한이 취소되었습니다", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ----------------------------------------------------------------
    //  크롤
    // ----------------------------------------------------------------
    private fun startCrawl() {
        if (crawling) return
        loadOptions()
        activeTabs = siteTabs.filter { it.second in enabledTypes }
        if (activeTabs.isEmpty()) {
            Toast.makeText(this, "옵션에서 크롤 대상을 한 개 이상 선택하세요", Toast.LENGTH_SHORT).show()
            return
        }
        crawling = true
        manualCapture = false
        manualDownload = false
        captureSavedTotal = 0
        collected.clear()
        progress.visibility = View.VISIBLE
        galleryArea.visibility = View.GONE
        web.visibility = View.VISIBLE
        // 자동 크롤 중에는 수동 버튼/탐색 탭을 숨겨 오작동을 막는다(완료 시 showResults가 복구)
        webTabs.visibility = View.GONE
        btnGallery.visibility = View.GONE
        btnDownload.visibility = View.GONE
        btnCapture.visibility = View.GONE
        btnLogin.visibility = View.GONE
        btnOptions.visibility = View.GONE
        if (method == METHOD_CAPTURE) {
            startCaptureStage()             // 화면 캡쳐만
        } else {
            stage = STAGE_DIRECT            // auto / direct: 먼저 URL 다운로드
            tabIndex = 0
            loadTab()
        }
    }

    private fun loadTab() {
        if (tabIndex >= activeTabs.size) {
            finishDirectStage()
            return
        }
        val (param, type) = activeTabs[tabIndex]
        tabResultReceived = false
        emptyRetries = 0
        setStatus("크롤링 중: ${tabLabel[type]} 탭…")
        web.loadUrl(baseUrl + param)
        armWatchdog()
    }

    /** 한 탭이 너무 오래 걸리면(멈춤/응답없음) 강제로 다음 탭으로 넘어간다. */
    private fun armWatchdog() {
        cancelWatchdog()
        val myIndex = tabIndex
        val w = Runnable {
            if (crawling && tabIndex == myIndex && !tabResultReceived) {
                tabResultReceived = true
                tabIndex++
                loadTab()
            }
        }
        watchdog = w
        handler.postDelayed(w, 55000)
    }

    private fun cancelWatchdog() {
        watchdog?.let { handler.removeCallbacks(it) }
        watchdog = null
    }

    private fun injectExtract() {
        if (crawling && !tabResultReceived) {
            web.evaluateJavascript(EXTRACT_JS, null)
        }
    }

    /** JS → Kotlin 결과 수신 (백그라운드 스레드 → UI 스레드로 전환) */
    inner class JsBridge {
        @JavascriptInterface
        fun onResult(json: String) {
            runOnUiThread {
                if (manualDownload) handleManualDownload(json)
                else handleTabResult(json)
            }
        }

        @JavascriptInterface
        fun onCapture(json: String) {
            runOnUiThread {
                if (manualCapture) handleManualCapture(json)
                else handleCaptureScan(json)
            }
        }

        @JavascriptInterface
        fun onProbe(json: String) {
            runOnUiThread { handleProbe(json) }
        }
    }

    private fun handleTabResult(json: String) {
        if (!crawling || tabResultReceived) return
        val type = activeTabs.getOrNull(tabIndex)?.second ?: "image"
        var count = 0
        var dbgStr = ""
        try {
            // 신규 형식 {items:[...], dbg:{...}} 와 구버전 [...] 둘 다 허용
            val arr: JSONArray
            if (json.trimStart().startsWith("{")) {
                val root = JSONObject(json)
                arr = root.optJSONArray("items") ?: JSONArray()
                root.optJSONObject("dbg")?.let { d ->
                    dbgStr = " · a=${d.optInt("a")} img=${d.optInt("img")}"
                }
            } else {
                arr = JSONArray(json)
            }
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val jobId = o.optString("jobId")
                val url = o.optString("url")
                val link = o.optString("link")
                if (jobId.isEmpty() || url.isEmpty()) continue
                count++
                if (!collected.containsKey(jobId)) {
                    collected[jobId] = Triple(url, link, type)
                }
            }
        } catch (_: Exception) {
        }

        // 이번 탭에서 0개면 CF/로딩 지연일 수 있으니 몇 번 재시도
        if (count == 0 && emptyRetries < 2) {
            emptyRetries++
            web.postDelayed({ injectExtract() }, 2500)
            return
        }

        tabResultReceived = true
        cancelWatchdog()
        setStatus("크롤링: ${tabLabel[type]} 탭 · 누적 ${collected.size}개$dbgStr")
        tabIndex++
        loadTab()
    }

    private fun finishDirectStage() {
        cancelWatchdog()
        setStatus("URL 다운로드 중…")
        // WebView 접근은 반드시 UI 스레드에서 — 백그라운드로 넘기기 전에 미리 읽어둔다.
        val ua = web.settings.userAgentString
        val cookie = CookieManager.getInstance().getCookie("https://www.midjourney.com")
        val today = todayStr()
        val snapshot = collected.toList() // (jobId, (url, link, type))

        Thread {
            val items = Storage.load(this)
            val have = HashSet<String>(items.size)
            items.forEach { have.add(it.jobId) }
            var added = 0
            for ((jobId, triple) in snapshot) {
                if (jobId in have) continue          // 중복(이미 받은 것) 제외
                val (url, link, type) = triple
                val ext = extOf(url)
                val fname = "$jobId.$ext"
                val dest = File(Storage.mediaDir(this), fname)
                if (download(url, dest, ua, cookie)) {
                    items.add(Item(jobId, jobId, type, url, link, fname, today))
                    have.add(jobId)
                    added++
                    // 폰 사진 앱의 'MJGallery' 앨범에도 누적 저장(옵션)
                    if (saveToAlbum) try { Gallery.save(this, dest, fname, mimeOf(ext)) } catch (_: Exception) {}
                    if (added % 4 == 0) {
                        val a = added
                        runOnUiThread { setStatus("다운로드 중… $a") }
                    }
                }
            }
            Storage.save(this, items)
            val newCount = added
            runOnUiThread {
                allItems = items
                rebuildDates()
                setStatus("URL 다운로드 완료 · 새로 ${newCount}장")
                // 다양한 방법으로 갤러리에 채운다:
                //  - 자동(AUTO): URL 다운로드 후 항상 화면 캡쳐로 누락분까지 보충
                //  - URL만(DIRECT): 크롤이 0장이면(차단/구조변경) 화면 캡쳐로 자동 폴백
                if (method == METHOD_AUTO || (method == METHOD_DIRECT && newCount == 0))
                    startCaptureStage()
                else
                    showResults()
            }
        }.start()
    }

    // ----------------------------------------------------------------
    //  화면 캡쳐(스크린샷) 방식 — URL 다운로드가 막히거나 실패할 때의 폴백
    //  (WebView에 실제 렌더된 화면을 비트맵으로 떠서 썸네일을 잘라 저장)
    // ----------------------------------------------------------------
    private fun startCaptureStage() {
        stage = STAGE_CAPTURE
        captureItems = Storage.load(this)
        captureHave = HashSet(captureItems.size)
        captureItems.forEach { captureHave.add(it.jobId) }
        capIndex = 0
        // API 26+ 는 PixelCopy(실제 합성된 화면 픽셀)로 캡쳐 → 하드웨어 레이어 그대로 둔다.
        // 그 미만에서만 WebView.draw() 폴백을 쓰므로 소프트웨어 레이어가 필요.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            try { web.setLayerType(View.LAYER_TYPE_SOFTWARE, null) } catch (_: Throwable) {}
        }
        loadCaptureTab()
    }

    private fun loadCaptureTab() {
        if (capIndex >= activeTabs.size) {
            finishCaptureStage()
            return
        }
        val (param, type) = activeTabs[capIndex]
        capType = type
        captureStarted = false
        setStatus("화면 캡쳐 중: ${tabLabel[type]} 탭…")
        web.loadUrl(baseUrl + param)
        armCaptureWatch()
    }

    private fun armCaptureWatch() {
        cancelCaptureWatch()
        val myIndex = capIndex
        val w = Runnable {
            if (crawling && stage == STAGE_CAPTURE && capIndex == myIndex) {
                captureStarted = false
                capIndex++
                loadCaptureTab()
            }
        }
        captureWatch = w
        handler.postDelayed(w, 60000)
    }

    private fun cancelCaptureWatch() {
        captureWatch?.let { handler.removeCallbacks(it) }
        captureWatch = null
    }

    private fun startCaptureScroll() {
        if (!crawling || stage != STAGE_CAPTURE || captureStarted) return
        captureStarted = true
        captureStep = 0
        web.evaluateJavascript("window.scrollTo(0,0);", null)
        handler.postDelayed({ requestCaptureScan() }, 900)
    }

    private fun requestCaptureScan() {
        if (!crawling || stage != STAGE_CAPTURE) return
        web.evaluateJavascript(CAPTURE_SCAN_JS, null) // 결과는 JsBridge.onCapture 로
    }

    /** JS가 보고한 화면 내 이미지 위치들을 받아 화면을 캡쳐 → 잘라 저장 (PixelCopy 우선, 비동기) */
    private fun handleCaptureScan(json: String) {
        if (!crawling || stage != STAGE_CAPTURE) return
        cancelCaptureWatch()
        var atBottom = true
        var imgTotal = 0
        var mjTotal = 0
        val ids = ArrayList<String>()
        val rects = ArrayList<IntArray>()
        try {
            val o = JSONObject(json)
            atBottom = o.optBoolean("atBottom", true)
            imgTotal = o.optInt("imgs")
            mjTotal = o.optInt("mj")
            val arr = o.optJSONArray("items") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val it = arr.getJSONObject(i)
                val id = it.optString("id")
                if (id.isEmpty()) continue
                ids.add(id)
                rects.add(intArrayOf(it.optInt("x"), it.optInt("y"), it.optInt("w"), it.optInt("h")))
            }
        } catch (_: Exception) {
        }
        val today = todayStr()
        val atBottomF = atBottom
        val imgF = imgTotal
        val mjF = mjTotal
        val myIndex = capIndex
        armCaptureWatch()                 // 비동기 캡쳐가 멈추면 다음 탭으로 넘어가도록 보호
        grabBitmap { bmp ->               // UI 스레드 콜백 (PixelCopy → 실패 시 draw 폴백)
            val capFailed = (bmp == null)
            Thread {
                var saved = 0
                if (bmp != null) {
                    for (k in ids.indices) {
                        if (cropAndSaveCapture(bmp, ids[k], rects[k], capType, today)) saved++
                    }
                    bmp.recycle()
                }
                val s = saved
                runOnUiThread {
                    if (!crawling || stage != STAGE_CAPTURE || capIndex != myIndex) return@runOnUiThread
                    cancelCaptureWatch()
                    captureSavedTotal += s
                    val diag = if (capFailed) " · ⚠캡쳐실패(폴백도실패)" else " · img${imgF}/mj${mjF}"
                    setStatus("화면 캡쳐: ${tabLabel[capType]} · 누적 ${captureSavedTotal}장$diag")
                    captureStep++
                    if (atBottomF || !scrollCapture || captureStep >= CAPTURE_MAX_STEPS) {
                        capIndex++
                        captureStarted = false
                        loadCaptureTab()
                    } else {
                        armCaptureWatch()
                        web.evaluateJavascript(
                            "window.scrollBy(0, Math.round(window.innerHeight*0.88));", null
                        )
                        handler.postDelayed({ requestCaptureScan() }, 900)
                    }
                }
            }.start()
        }
    }

    // ----------------------------------------------------------------
    //  수동 캡쳐 — 사용자가 로그인/탐색하다 사진이 보일 때 '📸 저장'을 누르면
    //  지금 화면에 보이는 MJ 이미지들을 잘라서 1회 저장한다. (가장 확실한 방법)
    // ----------------------------------------------------------------
    private fun captureCurrentScreen() {
        if (crawling) {
            Toast.makeText(this, "크롤 진행 중입니다. 끝난 뒤 시도하세요", Toast.LENGTH_SHORT).show()
            return
        }
        if (web.visibility != View.VISIBLE) {
            Toast.makeText(this, "‘🔍 탐색’으로 MJ 화면을 먼저 여세요(로그인 불필요)", Toast.LENGTH_SHORT).show()
            return
        }
        if (web.width <= 0 || web.height <= 0) {
            Toast.makeText(this, "화면 준비 중… 잠시 후 다시 누르세요", Toast.LENGTH_SHORT).show()
            return
        }
        manualCapture = true
        capType = inferTypeFromUrl()
        // 기존 저장분에 누적 + 중복 제외
        captureItems = Storage.load(this)
        captureHave = HashSet(captureItems.size)
        captureItems.forEach { captureHave.add(it.jobId) }
        setStatus("화면 저장 중…")
        web.evaluateJavascript(CAPTURE_SCAN_JS, null)   // → JsBridge.onCapture → handleManualCapture
    }

    /** 현재 보고 있는 URL로 카테고리를 추정 (모르면 image) */
    private fun inferTypeFromUrl(): String {
        val u = (web.url ?: "").lowercase()
        return when {
            u.contains("video") -> "video"
            u.contains("style") -> "style"
            else -> "image"
        }
    }

    /** '📸 저장' 1회성 처리 — 화면 캡쳐 → 보이는 이미지 잘라 저장 → 갤러리 갱신 */
    private fun handleManualCapture(json: String) {
        var mjTotal = 0
        val ids = ArrayList<String>()
        val rects = ArrayList<IntArray>()
        try {
            val o = JSONObject(json)
            mjTotal = o.optInt("mj")
            val arr = o.optJSONArray("items") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val it = arr.getJSONObject(i)
                val id = it.optString("id")
                if (id.isEmpty()) continue
                ids.add(id)
                rects.add(intArrayOf(it.optInt("x"), it.optInt("y"), it.optInt("w"), it.optInt("h")))
            }
        } catch (_: Exception) {
        }
        val today = todayStr()
        val mjF = mjTotal
        grabBitmap { bmp ->                       // UI 스레드 콜백 (PixelCopy → draw 폴백)
            val capFailed = (bmp == null)
            Thread {
                var saved = 0
                if (bmp != null) {
                    for (k in ids.indices) {
                        if (cropAndSaveCapture(bmp, ids[k], rects[k], capType, today)) saved++
                    }
                    bmp.recycle()
                }
                Storage.save(this, captureItems)
                val s = saved
                runOnUiThread {
                    manualCapture = false
                    allItems = captureItems
                    rebuildDates()
                    applyFilter()
                    val msg = when {
                        capFailed -> "⚠ 화면 캡쳐 실패 — 잠시 후 다시 ‘📸 저장’"
                        s > 0 -> "이 화면에서 ${s}장 저장 · 누적 ${allItems.size}장 · ‘🖼 갤러리’로 확인"
                        mjF == 0 -> "화면에 MJ 이미지가 없어요 — 사진이 보이게 스크롤 후 다시 ‘📸 저장’"
                        else -> "새로 저장할 새 이미지 없음(이미 저장됨) · 감지 ${mjF}개"
                    }
                    setStatus(msg)
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            }.start()
        }
    }

    // ----------------------------------------------------------------
    //  탐색 모드: '⬇ 다운' — 지금 보고 있는 페이지의 MJ 이미지 URL을 모아
    //  갤러리 폴더로 직접 다운로드. URL을 못 찾거나 0장이면 화면 캡쳐로 자동 폴백.
    // ----------------------------------------------------------------
    private fun downloadCurrentPage() {
        if (crawling) {
            Toast.makeText(this, "크롤 진행 중입니다. 끝난 뒤 시도하세요", Toast.LENGTH_SHORT).show()
            return
        }
        if (web.visibility != View.VISIBLE) {
            Toast.makeText(this, "‘🔍 탐색’으로 MJ 화면을 먼저 여세요(로그인 불필요)", Toast.LENGTH_SHORT).show()
            return
        }
        manualDownload = true
        setStatus("이 화면의 이미지 URL 수집 중…")
        // EXTRACT_JS: 페이지를 스크롤하며 /jobs/ 링크 + MJ CDN <img> URL 수집 → onResult
        web.evaluateJavascript(EXTRACT_JS, null)
    }

    /** '⬇ 다운' 결과 처리 — 수집된 URL들을 갤러리로 다운로드. 0장이면 화면 캡쳐 폴백. */
    private fun handleManualDownload(json: String) {
        manualDownload = false
        val type = inferTypeFromUrl()
        val snapshot = LinkedHashMap<String, Triple<String, String, String>>()
        try {
            val arr: JSONArray = if (json.trimStart().startsWith("{"))
                JSONObject(json).optJSONArray("items") ?: JSONArray()
            else JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val jobId = o.optString("jobId")
                val url = o.optString("url")
                val link = o.optString("link")
                if (jobId.isEmpty() || url.isEmpty()) continue
                if (!snapshot.containsKey(jobId)) snapshot[jobId] = Triple(url, link, type)
            }
        } catch (_: Exception) {
        }

        if (snapshot.isEmpty()) {
            // URL을 전혀 못 찾음(빈/차단 화면) → 화면 캡쳐로 폴백
            setStatus("이미지 URL을 못 찾음 — 화면 캡쳐로 저장 시도…")
            captureCurrentScreen()
            return
        }

        val ua = web.settings.userAgentString
        val cookie = CookieManager.getInstance().getCookie("https://www.midjourney.com")
        val today = todayStr()
        val list = snapshot.toList()
        setStatus("다운로드 중… (${list.size}개 후보)")
        Thread {
            val items = Storage.load(this)
            val have = HashSet<String>(items.size)
            items.forEach { have.add(it.jobId) }
            var added = 0
            for ((jobId, triple) in list) {
                if (jobId in have) continue
                val (url, link, t) = triple
                val ext = extOf(url)
                val fname = "$jobId.$ext"
                val dest = File(Storage.mediaDir(this), fname)
                if (download(url, dest, ua, cookie)) {
                    items.add(Item(jobId, jobId, t, url, link, fname, today))
                    have.add(jobId)
                    added++
                    if (saveToAlbum) try { Gallery.save(this, dest, fname, mimeOf(ext)) } catch (_: Exception) {}
                    if (added % 4 == 0) {
                        val a = added
                        runOnUiThread { setStatus("다운로드 중… $a") }
                    }
                }
            }
            Storage.save(this, items)
            val newCount = added
            runOnUiThread {
                allItems = items
                rebuildDates()
                if (newCount > 0) {
                    val msg = "이 화면에서 ${newCount}장 다운로드 · 누적 ${allItems.size}장 · ‘🖼 갤러리’로 확인"
                    setStatus(msg)
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                } else {
                    // 후보는 있었으나 새로 받은 게 0(중복이거나 차단) → 화면 캡쳐로 보충
                    setStatus("새 URL 다운로드 0장 — 화면 캡쳐로 보충 시도…")
                    captureCurrentScreen()
                }
            }
        }.start()
    }

    // ----------------------------------------------------------------
    //  빈 화면(차단) 감지 — 탐색 화면에 MJ 이미지가 안 보이면 대안을 제시
    //  (브라우저로 열기 / 전체화면 캡쳐 / 다시 시도). 자동 진단 메시지 포함.
    // ----------------------------------------------------------------
    private fun probeBlank() {
        if (crawling || web.visibility != View.VISIBLE) return
        web.evaluateJavascript(PROBE_JS, null)   // → JsBridge.onProbe → handleProbe
    }

    private fun handleProbe(json: String) {
        if (crawling || web.visibility != View.VISIBLE) return
        var mj = 0; var img = 0; var title = ""
        try {
            val o = JSONObject(json)
            mj = o.optInt("mj")
            img = o.optInt("img")
            title = o.optString("t")
        } catch (_: Exception) {
        }
        if (mj > 0) {
            blankProbeTries = 0
            setStatus("이미지 ${mj}개 표시됨 · ‘⬇ 다운’으로 저장 · ‘📸 저장’으로 화면 저장")
            return
        }
        // MJ 이미지 0 — 지연 로딩/CF 통과 대기일 수 있으니 몇 번 더 기다렸다 재확인
        if (blankProbeTries < 2) {
            blankProbeTries++
            setStatus("불러오는 중… 이미지 대기(${blankProbeTries}/2) · img=$img")
            web.postDelayed({ probeBlank() }, 3500)
            return
        }
        // 여전히 0 → 빈/차단 화면으로 판단하고 대안 제시(1회)
        setStatus("⚠ 이 화면에 MJ 이미지가 안 보임 · title='$title' img=$img · 대안 선택")
        if (!blankDialogShown) {
            blankDialogShown = true
            showBlankDialog(title, img)
        }
    }

    /** 빈/차단 화면일 때의 대안 — 브라우저로 보기 / 전체화면 캡쳐 / 다시 시도 */
    private fun showBlankDialog(title: String, img: Int) {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("이미지가 안 보여요")
            .setMessage(
                "이 앱 화면에서 MJ 이미지가 로드되지 않았어요(차단/지연일 수 있음).\n" +
                "진단: title='$title', img=$img\n\n" +
                "아래 방법으로 사진을 가져올 수 있어요:\n" +
                "• 브라우저로 열기 → 보고 다시 돌아와 ‘⬇ 다운/📸 저장’\n" +
                "• 전체화면 캡쳐 → MJ 앱·브라우저 화면을 갤러리에 저장\n" +
                "• 다시 시도 → 이 화면을 새로고침"
            )
            .setPositiveButton("브라우저로 열기") { _, _ -> openInBrowser() }
            .setNeutralButton("전체화면 캡쳐") { _, _ -> startScreenCapture() }
            .setNegativeButton("다시 시도") { _, _ ->
                blankProbeTries = 0
                blankDialogShown = false
                web.reload()
            }
            .show()
    }

    /**
     * 현재 화면을 비트맵으로 캡쳐해 onReady(UI 스레드)로 돌려준다.
     *  1순위: PixelCopy — 실제로 합성된 화면 픽셀을 읽으므로 하드웨어(GPU) 렌더링도 OK.
     *  실패/구버전(API<26): WebView.draw() 폴백. 둘 다 단색(검은) 화면이면 null.
     */
    private fun grabBitmap(onReady: (Bitmap?) -> Unit) {
        val w = web.width
        val h = web.height
        if (w <= 0 || h <= 0) { onReady(null); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val loc = IntArray(2)
                web.getLocationInWindow(loc)
                val src = android.graphics.Rect(loc[0], loc[1], loc[0] + w, loc[1] + h)
                android.view.PixelCopy.request(window, src, bmp, { result ->
                    if (result == android.view.PixelCopy.SUCCESS && !isBlankBitmap(bmp)) {
                        onReady(bmp)
                    } else {
                        bmp.recycle()
                        onReady(drawFallbackBitmap(w, h))
                    }
                }, handler)
                return
            } catch (_: Throwable) {
                // 아래 폴백으로
            }
        }
        onReady(drawFallbackBitmap(w, h))
    }

    /** WebView.draw() 폴백 (UI 스레드). 단색이면 null */
    private fun drawFallbackBitmap(w: Int, h: Int): Bitmap? {
        return try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            web.draw(Canvas(bmp))
            if (isBlankBitmap(bmp)) { bmp.recycle(); null } else bmp
        } catch (_: Throwable) {
            null
        }
    }

    /** 표본 픽셀이 전부 같은 색이면(검은/단색 화면) 캡쳐 실패로 간주 */
    private fun isBlankBitmap(bmp: Bitmap): Boolean {
        return try {
            val w = bmp.width
            val h = bmp.height
            if (w < 4 || h < 4) return true
            val xs = intArrayOf(w / 6, w / 3, w / 2, 2 * w / 3, 5 * w / 6)
            val ys = intArrayOf(h / 6, h / 3, h / 2, 2 * h / 3, 5 * h / 6)
            var first = 0
            var got = false
            for (x in xs) for (y in ys) {
                val p = bmp.getPixel(x, y)
                if (!got) { first = p; got = true }
                else if (p != first) return false
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** 스크린샷에서 한 이미지 영역을 잘라 jobId.jpg 로 저장(+갤러리). 중복/너무 작은 건 제외 */
    private fun cropAndSaveCapture(
        bmp: Bitmap, id: String, rect: IntArray, type: String, today: String
    ): Boolean {
        if (captureHave.contains(id)) return false
        val bw = bmp.width
        val bh = bmp.height
        var x = rect[0]; var y = rect[1]; var w = rect[2]; var h = rect[3]
        if (x < 0) { w += x; x = 0 }
        if (y < 0) { h += y; y = 0 }
        if (x >= bw || y >= bh) return false
        if (x + w > bw) w = bw - x
        if (y + h > bh) h = bh - y
        if (w < 40 || h < 40) return false
        val fname = "$id.jpg"
        val dest = File(Storage.mediaDir(this), fname)
        return try {
            val crop = Bitmap.createBitmap(bmp, x, y, w, h)
            FileOutputStream(dest).use { crop.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            crop.recycle()
            if (dest.length() <= 0L) {
                dest.delete()
                false
            } else {
                captureItems.add(Item(id, id, type, "", "", fname, today))
                captureHave.add(id)
                if (saveToAlbum) try { Gallery.save(this, dest, fname, "image/jpeg") } catch (_: Exception) {}
                true
            }
        } catch (_: Throwable) {
            if (dest.exists()) dest.delete()
            false
        }
    }

    private fun finishCaptureStage() {
        cancelCaptureWatch()
        try { web.setLayerType(View.LAYER_TYPE_NONE, null) } catch (_: Throwable) {}
        val snapshot = captureItems
        Thread {
            Storage.save(this, snapshot)
            runOnUiThread {
                allItems = snapshot
                showResults()
            }
        }.start()
    }

    /** 모든 단계 종료 후 갤러리 화면 복귀 */
    private fun showResults() {
        crawling = false
        manualCapture = false
        stage = STAGE_DIRECT
        captureStarted = false
        cancelWatchdog()
        cancelCaptureWatch()
        try { web.setLayerType(View.LAYER_TYPE_NONE, null) } catch (_: Throwable) {}
        progress.visibility = View.GONE
        showGalleryMode()
        rebuildDates()
        applyFilter()
        selectFirstNonEmptyTab()              // 저장분이 다른 카테고리면 그 탭을 자동 선택
        val total = allItems.size
        setStatus(
            if (total == 0)
                "0장 — ‘🔍 탐색’으로 MJ 접속(로그인 불필요) 후 사진이 보이면 ‘📸 저장’(가장 확실), 또는 ↻ 크롤"
            else
                "완료 · 총 ${total}장 · 사진을 누르면 확대"
        )
    }

    // ----------------------------------------------------------------
    //  옵션 (방식/대상/자동시작) — SharedPreferences 저장
    // ----------------------------------------------------------------
    private fun loadOptions() {
        method = prefs.getString(KEY_METHOD, METHOD_AUTO) ?: METHOD_AUTO
        val all = listOf("image", "style", "video")
        val def = all.toSet()
        val saved = prefs.getStringSet(KEY_TYPES, def) ?: def
        val filtered = all.filter { it in saved }
        enabledTypes = if (filtered.isEmpty()) linkedSetOf("image", "style", "video")
        else LinkedHashSet(filtered)
        autoStart = prefs.getBoolean(KEY_AUTOSTART, false)   // 기본 꺼짐: 앱 켜면 갤러리부터 보이게
        saveToAlbum = prefs.getBoolean(KEY_ALBUM, true)
        scrollCapture = prefs.getBoolean(KEY_SCROLL, true)
    }

    private fun saveOptions() {
        prefs.edit()
            .putString(KEY_METHOD, method)
            .putStringSet(KEY_TYPES, HashSet(enabledTypes))
            .putBoolean(KEY_AUTOSTART, autoStart)
            .putBoolean(KEY_ALBUM, saveToAlbum)
            .putBoolean(KEY_SCROLL, scrollCapture)
            .apply()
    }

    private fun showOptionsDialog() {
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun header(t: String) = TextView(this).apply {
            text = t
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, (8 * d).toInt(), 0, (4 * d).toInt())
        }
        lateinit var dlg: AlertDialog

        // 크롤 방식 (단일 선택)
        root.addView(header("크롤 방식"))
        val methodKeys = arrayOf(METHOD_AUTO, METHOD_DIRECT, METHOD_CAPTURE)
        val methodLabels = arrayOf(
            "자동 (URL 다운로드 → 실패 시 화면 캡쳐)",
            "URL 다운로드만",
            "화면 캡쳐만 (스크린샷)"
        )
        val rg = android.widget.RadioGroup(this)
        for (i in methodKeys.indices) {
            rg.addView(android.widget.RadioButton(this).apply {
                id = i + 1
                text = methodLabels[i]
            })
        }
        rg.check(methodKeys.indexOf(method).coerceAtLeast(0) + 1)
        root.addView(rg)

        // 크롤 대상 (다중 선택)
        root.addView(header("크롤 대상 카테고리"))
        val typeKeys = arrayOf("image", "style", "video")
        val typeLabels = arrayOf("이미지", "스타일", "비디오")
        val boxes = typeKeys.indices.map { i ->
            android.widget.CheckBox(this).apply {
                text = typeLabels[i]
                isChecked = typeKeys[i] in enabledTypes
            }
        }
        boxes.forEach { root.addView(it) }

        // 저장 · 기타
        root.addView(header("저장 · 기타"))
        val albumBox = android.widget.CheckBox(this).apply {
            text = "폰 갤러리(MJGallery 앨범)에도 저장"
            isChecked = saveToAlbum
        }
        root.addView(albumBox)
        val scrollBox = android.widget.CheckBox(this).apply {
            text = "스크롤하며 여러 화면 캡쳐 (끄면 현재 화면만)"
            isChecked = scrollCapture
        }
        root.addView(scrollBox)
        val autoBox = android.widget.CheckBox(this).apply {
            text = "앱 실행 시 자동 크롤 시작 (기본 꺼짐)"
            isChecked = autoStart
        }
        root.addView(autoBox)

        // 외부 앱/브라우저로 가져오기 (이 앱 WebView에서 안 보일 때의 대안)
        root.addView(header("외부 앱/브라우저로 가져오기"))
        root.addView(TextView(this).apply {
            text = "이 앱에서 사진이 안 보이면: MJ 앱이나 브라우저로 열어서 보고, " +
                "‘전체화면 캡쳐’로 그 화면(MJ 앱/브라우저 포함)을 갤러리에 저장하세요."
            textSize = 12f
            setTextColor(0xFF999999.toInt())
            setPadding(0, 0, 0, (6 * d).toInt())
        })
        fun actionBtn(label: String, run: () -> Unit) = android.widget.Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { dlg.dismiss(); run() }
        }
        root.addView(actionBtn("MJ 앱으로 열기") { openMjApp() })
        root.addView(actionBtn("브라우저로 열기") { openInBrowser() })
        root.addView(actionBtn("전체화면 캡쳐 시작 (MJ앱·브라우저도 가능)") { startScreenCapture() })
        root.addView(actionBtn("전체화면 캡쳐 중지") { stopScreenCapture() })
        root.addView(actionBtn("(선택) 떠다니는 캡쳐 버튼 권한") { openOverlaySettings() })

        val scroll = android.widget.ScrollView(this).apply { addView(root) }

        dlg = AlertDialog.Builder(this)
            .setTitle("옵션")
            .setView(scroll)
            .setNegativeButton("취소", null)
            .setPositiveButton("저장") { _, _ ->
                val mIdx = (rg.checkedRadioButtonId - 1).coerceIn(0, methodKeys.size - 1)
                method = methodKeys[mIdx]
                val set = LinkedHashSet<String>()
                for (i in typeKeys.indices) if (boxes[i].isChecked) set.add(typeKeys[i])
                enabledTypes = if (set.isEmpty()) linkedSetOf("image", "style", "video") else set
                saveToAlbum = albumBox.isChecked
                scrollCapture = scrollBox.isChecked
                autoStart = autoBox.isChecked
                saveOptions()
                Toast.makeText(this, "옵션 저장됨", Toast.LENGTH_SHORT).show()
            }
            .create()
        dlg.show()
    }

    // ----------------------------------------------------------------
    //  필터 (카테고리 탭 + 날짜)
    // ----------------------------------------------------------------
    private fun applyFilter() {
        val filtered = allItems.filter {
            it.type == currentType && (currentDate == ALL || it.date == currentDate)
        }
        adapter.replaceAll(filtered)
        updateEmpty()
    }

    private fun rebuildDates() {
        val dates = allItems.map { it.date }.filter { it.isNotBlank() }
            .distinct().sortedDescending()
        val options = ArrayList<String>()
        options.add(ALL)
        options.addAll(dates)
        dateOptions = options

        val ad = ArrayAdapter(this, R.layout.spinner_item, options)
        ad.setDropDownViewResource(R.layout.spinner_item)

        settingSpinner = true
        dateSpinner.adapter = ad
        var idx = options.indexOf(currentDate)
        if (idx < 0) idx = 0
        currentDate = options[idx]
        dateSpinner.setSelection(idx)
        settingSpinner = false
    }

    // ----------------------------------------------------------------
    //  삭제 (로컬 저장소에서 제거)
    // ----------------------------------------------------------------
    private fun onDeleteItem(item: Item) {
        val items = Storage.load(this)
        Storage.delete(this, items, item)        // 파일 삭제 + metadata 저장
        allItems.removeAll { it.id == item.id }
        rebuildDates()                            // 비워진 날짜는 목록에서 제거
        applyFilter()
        Toast.makeText(this, "삭제됨", Toast.LENGTH_SHORT).show()
    }

    /** 어댑터가 선택 모드/선택 개수가 바뀔 때마다 호출 → 액션 바 갱신 */
    private fun onSelectionChanged(mode: Boolean, count: Int) {
        selectionBar.visibility = if (mode) View.VISIBLE else View.GONE
        selCount.text = "${count}개 선택"
    }

    /** 선택된 여러 장을 한 번에 삭제 (확인 후) */
    private fun bulkDelete() {
        val targets = adapter.selectedItems()
        if (targets.isEmpty()) {
            Toast.makeText(this, "선택된 항목이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("삭제 확인")
            .setMessage("${targets.size}장을 삭제할까요? (되돌릴 수 없음)")
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제") { _, _ ->
                val ids = targets.map { it.id }.toHashSet()
                // 파일 삭제
                for (t in targets) {
                    try {
                        val f = File(Storage.mediaDir(this), t.file)
                        if (f.exists()) f.delete()
                    } catch (_: Exception) {}
                }
                // metadata 한 번만 저장
                val items = Storage.load(this)
                items.removeAll { it.id in ids }
                Storage.save(this, items)
                allItems.removeAll { it.id in ids }
                adapter.exitSelection()
                rebuildDates()
                applyFilter()
                Toast.makeText(this, "${targets.size}장 삭제됨", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ----------------------------------------------------------------
    //  사진 탭 → 전체화면(확대) 뷰어
    // ----------------------------------------------------------------
    private fun onOpenItem(item: Item) {
        val f = File(Storage.mediaDir(this), item.file)
        if (!f.exists()) {
            Toast.makeText(this, "파일을 찾을 수 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        val i = Intent(this, FullscreenActivity::class.java)
        i.putExtra("file", f.absolutePath)
        startActivity(i)
    }

    // ----------------------------------------------------------------
    //  유틸
    // ----------------------------------------------------------------
    private fun setStatus(msg: String) {
        status.text = msg
        status.visibility = View.VISIBLE
    }

    private fun updateEmpty() {
        empty.text = if (currentType == "capture")
            "‘캡쳐’ 항목이 없습니다.\n옵션 → ‘전체화면 캡쳐 시작’ 후 MJ 앱/브라우저에서 ‘📸 캡쳐’"
        else
            "‘${tabLabel[currentType]}’ 항목이 없습니다.\n↻ 크롤 버튼을 눌러주세요"
        empty.visibility = if (adapter.itemCount == 0 && !crawling) View.VISIBLE else View.GONE
    }

    private fun todayStr(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun extOf(url: String): String {
        return try {
            val path = URL(url).path
            val dot = path.lastIndexOf('.')
            if (dot >= 0 && dot < path.length - 1) {
                val e = path.substring(dot + 1).lowercase()
                if (e.length in 2..4 && e.all { it.isLetterOrDigit() }) e else "webp"
            } else "webp"
        } catch (_: Exception) {
            "webp"
        }
    }

    private fun mimeOf(ext: String): String = when (ext.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        else -> "image/webp"
    }

    private fun download(urlStr: String, dest: File, ua: String?, cookie: String?): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 25000
            conn.instanceFollowRedirects = true
            if (!ua.isNullOrEmpty()) conn.setRequestProperty("User-Agent", ua)
            if (!cookie.isNullOrEmpty()) conn.setRequestProperty("Cookie", cookie)
            conn.setRequestProperty("Referer", "https://www.midjourney.com/")
            conn.setRequestProperty("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
            conn.connect()
            if (conn.responseCode in 200..299) {
                conn.inputStream.use { input ->
                    FileOutputStream(dest).use { out -> input.copyTo(out) }
                }
                dest.length() > 0
            } else {
                if (dest.exists()) dest.delete()
                false
            }
        } catch (_: Exception) {
            if (dest.exists()) dest.delete()
            false
        } finally {
            conn?.disconnect()
        }
    }

    override fun onResume() {
        super.onResume()
        // MJ 앱/브라우저에서 '전체화면 캡쳐'로 저장된 새 항목을, 앱으로 돌아오면 자동 반영.
        // 웹뷰/크롤/선택 모드 중에는 건드리지 않는다(불필요한 새로고침/깜빡임 방지).
        if (crawling) return
        if (galleryArea.visibility != View.VISIBLE) return
        if (::adapter.isInitialized && adapter.selectionMode) return
        val fresh = Storage.load(this)
        if (fresh.size != allItems.size) {        // 개수가 달라졌을 때만(새 캡쳐 등) 갱신
            allItems = fresh
            rebuildDates()
            applyFilter()
            selectFirstNonEmptyTab()
            setStatus("총 ${allItems.size}장 · 사진을 누르면 확대 · 길게 눌러 여러 장 선택/삭제")
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // 1) 선택 모드면 먼저 해제
        if (::adapter.isInitialized && adapter.selectionMode) {
            adapter.exitSelection()
            return
        }
        // 2) 웹뷰가 보이는 상태면 '뒤로가기'이지 '앱 종료'가 아니다.
        if (web.visibility == View.VISIBLE) {
            if (crawling) {
                // 크롤/캡쳐 진행 중 → 중단하고 갤러리로 (종료 X)
                cancelCrawl()
                return
            }
            // 탐색 중 → 웹 히스토리 뒤로, 없으면 갤러리로 복귀 (종료 X)
            if (web.canGoBack()) web.goBack() else backToGallery()
            return
        }
        super.onBackPressed()
    }

    /** 진행 중인 크롤/캡쳐를 중단하고 갤러리로 복귀 (백키/사용자 중단용) */
    private fun cancelCrawl() {
        crawling = false
        manualCapture = false
        manualDownload = false
        stage = STAGE_DIRECT
        captureStarted = false
        cancelWatchdog()
        cancelCaptureWatch()
        progress.visibility = View.GONE
        try { web.setLayerType(View.LAYER_TYPE_NONE, null) } catch (_: Throwable) {}
        allItems = Storage.load(this)
        showGalleryMode()
        rebuildDates()
        applyFilter()
        selectFirstNonEmptyTab()
        setStatus("크롤 중단됨 · 총 ${allItems.size}장 · 사진을 누르면 확대")
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        cancelWatchdog()
        handler.removeCallbacksAndMessages(null)
        web.removeJavascriptInterface("AndroidCrawler")
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val ALL = "전체"

        // 화면 캡쳐 권한 요청 코드
        private const val REQ_PROJECTION = 7001

        // 크롤 방식
        private const val METHOD_AUTO = "auto"
        private const val METHOD_DIRECT = "direct"
        private const val METHOD_CAPTURE = "capture"

        // 단계
        private const val STAGE_DIRECT = 0
        private const val STAGE_CAPTURE = 1

        // SharedPreferences 키
        private const val KEY_METHOD = "method"
        private const val KEY_TYPES = "types"
        private const val KEY_AUTOSTART = "autostart"
        private const val KEY_ALBUM = "save_album"
        private const val KEY_SCROLL = "scroll_capture"

        // 캡쳐: 한 탭에서 최대 스크롤(스크린샷) 횟수
        private const val CAPTURE_MAX_STEPS = 30

        /**
         * 현재 화면에 보이는 midjourney <img>들의 화면상 위치(devicePixel)를 모아
         * AndroidCrawler.onCapture(JSON) 으로 돌려준다. (스크롤은 Kotlin이 제어)
         */
        private const val CAPTURE_SCAN_JS = """
(function(){
  try {
    var dpr = window.devicePixelRatio || 1;
    var H = window.innerHeight;
    var UUID = /([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/i;
    var res = [];
    var imgs = document.querySelectorAll('img');
    var mj = 0;
    for (var i=0;i<imgs.length;i++){
      var im = imgs[i];
      var s = im.currentSrc || im.src || '';
      if (s.indexOf('midjourney') === -1) continue;
      mj++;
      var m = s.match(UUID);
      if (!m) continue;
      var r = im.getBoundingClientRect();
      if (r.bottom <= 0 || r.top >= H) continue;
      if (r.width < 50 || r.height < 50) continue;
      res.push({id:m[1],
                x:Math.round(r.left*dpr), y:Math.round(r.top*dpr),
                w:Math.round(r.width*dpr), h:Math.round(r.height*dpr)});
    }
    var atBottom = (window.innerHeight + window.scrollY) >= (document.body.scrollHeight - 4);
    AndroidCrawler.onCapture(JSON.stringify({items:res, atBottom:atBottom, imgs:imgs.length, mj:mj}));
  } catch(e){
    try { AndroidCrawler.onCapture(JSON.stringify({items:[], atBottom:true, imgs:0, mj:0})); } catch(e2){}
  }
})();
"""

        /**
         * 빈/차단 화면 감지용: 현재 페이지의 전체 <img> 수, MJ CDN 이미지 수,
         * 문서 제목을 세어 AndroidCrawler.onProbe(JSON) 으로 돌려준다.
         */
        private const val PROBE_JS = """
(function(){
  try {
    var imgs = document.querySelectorAll('img');
    var mj = 0;
    for (var i=0;i<imgs.length;i++){
      var s = imgs[i].currentSrc || imgs[i].src || '';
      if (s.indexOf('midjourney') !== -1) mj++;
    }
    AndroidCrawler.onProbe(JSON.stringify({mj:mj, img:imgs.length, t:(document.title||'').slice(0,60)}));
  } catch(e){
    try { AndroidCrawler.onProbe(JSON.stringify({mj:0, img:0, t:''})); } catch(e2){}
  }
})();
"""

        /**
         * 페이지를 끝까지 스크롤하며 /jobs/ 링크와 썸네일 URL(속성값)을 모아
         * AndroidCrawler.onResult(JSON) 으로 돌려준다.
         * - 항목이 늘다가 멈추면 종료(STABLE_NEEDED)
         * - 0개면 ZERO_MAX 만큼만 시도하고 빠르게 빠져나옴(CF/빈 페이지)
         */
        private const val EXTRACT_JS = """
(function(){
  if (window.__mjCrawling) return;
  window.__mjCrawling = true;
  var lastCount = 0, stable = 0, ticks = 0;
  var MAX_TICKS = 50, STABLE_NEEDED = 4, ZERO_MAX = 10;
  var UUID = /([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/i;
  function abs(u){ try { return new URL(u, location.href).href; } catch(e){ return u; } }
  function deProxy(u){
    try {
      if (u && u.indexOf('/_next/image') !== -1){
        var q = u.split('?')[1] || '';
        var ps = q.split('&');
        for (var k=0;k<ps.length;k++){
          var kv = ps[k].split('=');
          if (kv[0] === 'url') return decodeURIComponent(kv[1] || '');
        }
      }
    } catch(e){}
    return u;
  }
  function jobOf(url, link){
    var mm = (link || '').match(/\/jobs\/([0-9a-fA-F\-]{8,})/);
    if (mm) return mm[1];
    mm = (url || '').match(UUID);
    if (mm) return mm[1];
    return '';
  }
  function imgUrl(a){
    var v = a.querySelector('video');
    if (v && v.poster) return abs(v.poster);
    var img = a.querySelector('img');
    if (img){
      var u = img.currentSrc || img.src || '';   // 프로퍼티 → 절대경로
      if (!u || u.indexOf('data:') === 0){
        var ss = img.getAttribute('srcset');
        if (ss){
          var parts = ss.split(',');
          var last = parts[parts.length-1].trim().split(' ')[0];
          if (last) u = abs(last);
        }
      }
      if (!u || u.indexOf('data:') === 0){
        var ds = img.getAttribute('data-src');
        if (ds) u = abs(ds);
      }
      if (u && u.indexOf('data:') !== 0) return u;
    }
    var bg = a.querySelector('[style*="background-image"]');
    if (bg){
      var s = bg.getAttribute('style') || '';
      var bm = s.match(/url\(["']?(.*?)["']?\)/);
      if (bm) return abs(bm[1]);
    }
    return '';
  }
  function pickImg(img){
    var u = img.currentSrc || img.src || '';
    if (!u || u.indexOf('data:') === 0){
      var ss = img.getAttribute('srcset');
      if (ss){
        var parts = ss.split(',');
        var last = parts[parts.length-1].trim().split(' ')[0];
        if (last) u = abs(last);
      }
    }
    if (!u || u.indexOf('data:') === 0){
      var ds = img.getAttribute('data-src');
      if (ds) u = abs(ds);
    }
    if (!u || u.indexOf('data:') === 0) return '';
    return deProxy(abs(u));
  }
  function collect(){
    var out = [], seen = {};
    // 1) /jobs/ 링크 우선 (있으면 가장 정확)
    var anchors = document.querySelectorAll('a[href*="/jobs/"]');
    for (var i=0;i<anchors.length;i++){
      var a = anchors[i];
      var href = a.href || '';
      var url = deProxy(imgUrl(a));
      var jobId = jobOf(url, href);
      if (!jobId || !url) continue;
      if (seen[jobId]) continue;
      seen[jobId] = 1;
      out.push({jobId: jobId, url: url, link: href});
    }
    // 2) 폴백: midjourney CDN <img> 전부 (그리드/모달 구조와 무관하게 UUID로 식별)
    var imgs = document.querySelectorAll('img');
    for (var j=0;j<imgs.length;j++){
      var u2 = pickImg(imgs[j]);
      if (!u2 || u2.indexOf('midjourney') === -1) continue;
      var id2 = jobOf(u2, '');
      if (!id2 || seen[id2]) continue;
      seen[id2] = 1;
      out.push({jobId: id2, url: u2, link: ''});
    }
    return out;
  }
  function dbg(){
    return {
      a: document.querySelectorAll('a[href*="/jobs/"]').length,
      img: document.querySelectorAll('img').length,
      t: (document.title || '').slice(0, 40)
    };
  }
  function tick(){
    ticks++;
    try { window.scrollTo(0, document.body.scrollHeight); } catch(e){}
    var items = collect();
    if (items.length > 0 && items.length === lastCount) stable++; else stable = 0;
    lastCount = items.length;
    var done = (items.length > 0 && stable >= STABLE_NEEDED)
               || ticks >= MAX_TICKS
               || (items.length === 0 && ticks >= ZERO_MAX);
    if (done){
      window.__mjCrawling = false;
      try { AndroidCrawler.onResult(JSON.stringify({items: items, dbg: dbg()})); } catch(e){}
      return;
    }
    setTimeout(tick, 700);
  }
  setTimeout(tick, 900);
})();
"""
    }
}
