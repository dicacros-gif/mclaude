package com.mclaude.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.tabs.TabLayout
import org.json.JSONArray
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
    private lateinit var dateSpinner: Spinner
    private lateinit var adapter: GalleryAdapter

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

    // 크롤할 사이트 탭: (탭 파라미터, 항목 type)
    private val siteTabs = listOf(
        "top" to "image",
        "styles_top" to "style",
        "video_top" to "video"
    )
    private val tabLabel = mapOf("image" to "이미지", "style" to "스타일", "video" to "비디오")
    private val baseUrl = "https://www.midjourney.com/explore?tab="

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
        dateSpinner = findViewById(R.id.dateSpinner)

        // --- 갤러리 ---
        grid.layoutManager = GridLayoutManager(this, 3)
        adapter = GalleryAdapter(this, mutableListOf(), ::onDeleteItem)
        grid.adapter = adapter
        allItems = Storage.load(this)

        // 카테고리 탭(항상 보임)
        tabsView.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentType = when (tab.position) {
                    1 -> "style"
                    2 -> "video"
                    else -> "image"
                }
                applyFilter()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        // 날짜 드롭다운
        dateSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (settingSpinner) return
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
        web.settings.mediaPlaybackRequiresUserGesture = true
        // 추출은 DOM의 src/srcset 속성만 읽으면 되므로 실제 이미지 디코딩은 끔(메모리/멈춤 방지)
        web.settings.loadsImagesAutomatically = false
        web.addJavascriptInterface(JsBridge(), "AndroidCrawler")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // CF 챌린지가 리다이렉트 후 실제 페이지를 다시 로드할 수 있으므로
                // 결과를 받기 전이면 페이지가 끝날 때마다 재주입한다.
                if (crawling && !tabResultReceived) {
                    web.postDelayed({ injectExtract() }, 1200)
                }
            }
        }

        btnLogin.setOnClickListener { openLogin() }
        btnCrawl.setOnClickListener { startCrawl() }
        swipe.setOnRefreshListener {
            swipe.isRefreshing = false
            startCrawl()
        }

        // 앱 실행 시 자동 크롤 시작
        handler.postDelayed({ startCrawl() }, 600)
    }

    // ----------------------------------------------------------------
    //  로그인 (사용자가 직접 MJ 로그인 / CF 통과)
    // ----------------------------------------------------------------
    private fun openLogin() {
        crawling = false
        cancelWatchdog()
        progress.visibility = View.GONE
        setStatus("로그인 후 다시 ↻ 크롤 버튼을 눌러주세요")
        galleryArea.visibility = View.GONE
        web.visibility = View.VISIBLE
        web.loadUrl("https://www.midjourney.com/explore?tab=top")
    }

    // ----------------------------------------------------------------
    //  크롤
    // ----------------------------------------------------------------
    private fun startCrawl() {
        if (crawling) return
        crawling = true
        tabIndex = 0
        collected.clear()
        progress.visibility = View.VISIBLE
        galleryArea.visibility = View.GONE
        web.visibility = View.VISIBLE
        loadTab()
    }

    private fun loadTab() {
        if (tabIndex >= siteTabs.size) {
            finishCrawl()
            return
        }
        val (param, type) = siteTabs[tabIndex]
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
            runOnUiThread { handleTabResult(json) }
        }
    }

    private fun handleTabResult(json: String) {
        if (!crawling || tabResultReceived) return
        val type = siteTabs.getOrNull(tabIndex)?.second ?: "image"
        var count = 0
        try {
            val arr = JSONArray(json)
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
        setStatus("크롤링: ${tabLabel[type]} 탭 · 누적 ${collected.size}개")
        tabIndex++
        loadTab()
    }

    private fun finishCrawl() {
        cancelWatchdog()
        web.visibility = View.GONE
        setStatus("다운로드 중…")
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
                    if (added % 4 == 0) {
                        val a = added
                        runOnUiThread { setStatus("다운로드 중… $a") }
                    }
                }
            }
            Storage.save(this, items)
            val total = items.size
            val newCount = added
            runOnUiThread {
                crawling = false
                allItems = items
                progress.visibility = View.GONE
                web.visibility = View.GONE
                galleryArea.visibility = View.VISIBLE
                rebuildDates()
                applyFilter()
                setStatus(
                    if (total == 0)
                        "이미지가 없습니다 — 상단 ‘로그인’ 후 다시 ↻ 크롤 해주세요"
                    else
                        "완료 · 새로 ${newCount}장 · 총 ${total}장"
                )
            }
        }.start()
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

    // ----------------------------------------------------------------
    //  유틸
    // ----------------------------------------------------------------
    private fun setStatus(msg: String) {
        status.text = msg
        status.visibility = View.VISIBLE
    }

    private fun updateEmpty() {
        empty.text = "‘${tabLabel[currentType]}’ 항목이 없습니다.\n↻ 크롤 버튼을 눌러주세요"
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
  var MAX_TICKS = 50, STABLE_NEEDED = 4, ZERO_MAX = 8;
  function abs(u){ try { return new URL(u, location.href).href; } catch(e){ return u; } }
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
  function collect(){
    var out = [], seen = {};
    var anchors = document.querySelectorAll('a[href*="/jobs/"]');
    for (var i=0;i<anchors.length;i++){
      var a = anchors[i];
      var href = a.href || '';
      var m = href.match(/\/jobs\/([0-9a-fA-F\-]{8,})/);
      if (!m) continue;
      var jobId = m[1];
      if (seen[jobId]) continue;
      var url = imgUrl(a);
      if (!url) continue;
      seen[jobId] = 1;
      out.push({jobId: jobId, url: url, link: href});
    }
    return out;
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
      try { AndroidCrawler.onResult(JSON.stringify(items)); } catch(e){}
      return;
    }
    setTimeout(tick, 700);
  }
  setTimeout(tick, 900);
})();
"""
    }
}
