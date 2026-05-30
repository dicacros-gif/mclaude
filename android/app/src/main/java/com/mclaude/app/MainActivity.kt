package com.mclaude.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var grid: RecyclerView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var empty: TextView
    private lateinit var btnLogin: Button
    private lateinit var btnCrawl: Button
    private lateinit var adapter: GalleryAdapter

    // 크롤 상태
    private var crawling = false
    private var loginMode = false
    private var injected = false
    private var tabIndex = 0
    private val collected = LinkedHashMap<String, Triple<String, String, String>>() // jobId -> (url, link, type)

    // 탭 정의: (탭 파라미터, 항목 type)
    private val tabs = listOf(
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
        grid = findViewById(R.id.grid)
        swipe = findViewById(R.id.swipe)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        empty = findViewById(R.id.empty)
        btnLogin = findViewById(R.id.btnLogin)
        btnCrawl = findViewById(R.id.btnCrawl)

        // --- 갤러리 ---
        grid.layoutManager = GridLayoutManager(this, 3)
        adapter = GalleryAdapter(this, Storage.load(this), ::onDeleteItem)
        grid.adapter = adapter
        updateEmpty()

        // --- WebView ---
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.databaseEnabled = true
        web.settings.loadsImagesAutomatically = true
        web.addJavascriptInterface(JsBridge(), "AndroidCrawler")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (crawling && !injected) {
                    injected = true
                    // CF/렌더링에 시간을 준 뒤 추출 스크립트 주입
                    web.postDelayed({ web.evaluateJavascript(EXTRACT_JS, null) }, 1500)
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
        web.postDelayed({ startCrawl() }, 600)
    }

    // ----------------------------------------------------------------
    //  로그인 (사용자가 직접 MJ 로그인 / CF 통과)
    // ----------------------------------------------------------------
    private fun openLogin() {
        crawling = false
        loginMode = true
        progress.visibility = View.GONE
        setStatus("로그인 후 다시 ↻ 크롤 버튼을 눌러주세요")
        swipe.visibility = View.GONE
        empty.visibility = View.GONE
        web.visibility = View.VISIBLE
        web.loadUrl("https://www.midjourney.com/explore?tab=top")
    }

    // ----------------------------------------------------------------
    //  크롤
    // ----------------------------------------------------------------
    private fun startCrawl() {
        if (crawling) return
        crawling = true
        loginMode = false
        tabIndex = 0
        collected.clear()
        progress.visibility = View.VISIBLE
        swipe.visibility = View.GONE
        empty.visibility = View.GONE
        web.visibility = View.VISIBLE
        loadTab()
    }

    private fun loadTab() {
        if (tabIndex >= tabs.size) {
            finishCrawl()
            return
        }
        val (param, type) = tabs[tabIndex]
        injected = false
        setStatus("크롤링 중: ${tabLabel[type]} 탭…")
        web.loadUrl(baseUrl + param)
    }

    /** JS → Kotlin 결과 수신 */
    inner class JsBridge {
        @JavascriptInterface
        fun onResult(json: String) {
            runOnUiThread { handleTabResult(json) }
        }
    }

    private fun handleTabResult(json: String) {
        if (!crawling) return
        val type = tabs.getOrNull(tabIndex)?.second ?: "image"
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val jobId = o.optString("jobId")
                val url = o.optString("url")
                val link = o.optString("link")
                if (jobId.isEmpty() || url.isEmpty()) continue
                if (!collected.containsKey(jobId)) {
                    collected[jobId] = Triple(url, link, type)
                }
            }
        } catch (_: Exception) {
        }
        setStatus("크롤링: ${tabLabel[type]} 탭 ${collected.size}개 수집됨")
        tabIndex++
        loadTab()
    }

    private fun finishCrawl() {
        web.visibility = View.GONE
        setStatus("다운로드 중…")
        val snapshot = collected.toList() // (jobId, (url, link, type))
        Thread {
            val items = Storage.load(this)
            val have = items.map { it.jobId }.toHashSet()
            val ua = web.settings.userAgentString
            val cookie = CookieManager.getInstance().getCookie("https://www.midjourney.com")
            var added = 0
            for ((jobId, triple) in snapshot) {
                if (jobId in have) continue
                val (url, link, type) = triple
                val ext = extOf(url)
                val fname = "$jobId.$ext"
                val dest = File(Storage.mediaDir(this), fname)
                if (download(url, dest, ua, cookie)) {
                    items.add(Item(id = jobId, jobId = jobId, type = type, url = url, link = link, file = fname))
                    have.add(jobId)
                    added++
                    val a = added
                    runOnUiThread { setStatus("다운로드 중… $a") }
                }
            }
            Storage.save(this, items)
            val total = items.size
            val newCount = added
            runOnUiThread {
                crawling = false
                progress.visibility = View.GONE
                swipe.visibility = View.VISIBLE
                adapter.replaceAll(Storage.load(this))
                updateEmpty()
                if (total == 0) {
                    setStatus("이미지가 없습니다 — 로그인이 필요할 수 있어요. ‘로그인’을 눌러주세요")
                } else {
                    setStatus("완료 · 새로 $newCount장 · 총 ${total}장")
                }
            }
        }.start()
    }

    // ----------------------------------------------------------------
    //  삭제 (로컬 저장소에서 제거)
    // ----------------------------------------------------------------
    private fun onDeleteItem(item: Item) {
        val items = Storage.load(this)
        Storage.delete(this, items, item) // 파일 삭제 + metadata 저장
        adapter.removeItem(item)
        updateEmpty()
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
        if (adapter.itemCount == 0 && !crawling) {
            empty.text = "아직 이미지가 없습니다.\n↻ 크롤 버튼을 눌러주세요"
            empty.visibility = View.VISIBLE
            swipe.visibility = View.GONE
        } else {
            empty.visibility = View.GONE
            if (!crawling) swipe.visibility = View.VISIBLE
        }
    }

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
        web.removeJavascriptInterface("AndroidCrawler")
        web.destroy()
        super.onDestroy()
    }

    companion object {
        /**
         * 페이지를 끝까지 스크롤하며 /jobs/ 링크와 썸네일 URL을 모아
         * AndroidCrawler.onResult(JSON) 으로 돌려준다.
         * 항목 증가가 멈추면(STABLE_NEEDED) 조기 종료 → 빈 페이지/CF 차단 시 빠르게 빠져나옴.
         */
        private const val EXTRACT_JS = """
(function(){
  if (window.__mjCrawling) return;
  window.__mjCrawling = true;
  var lastCount = 0, stable = 0, ticks = 0;
  var MAX_TICKS = 60, STABLE_NEEDED = 5;
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
      var url = '';
      var vid = a.querySelector('video');
      if (vid && vid.poster) url = vid.poster;
      if (!url){
        var img = a.querySelector('img');
        if (img) url = img.currentSrc || img.src || '';
      }
      if (!url){
        var bg = a.querySelector('[style*="background-image"]');
        if (bg){
          var s = bg.getAttribute('style') || '';
          var bm = s.match(/url\(["']?(.*?)["']?\)/);
          if (bm) url = bm[1];
        }
      }
      if (!url) continue;
      seen[jobId] = 1;
      out.push({jobId: jobId, url: url, link: href});
    }
    return out;
  }
  function tick(){
    ticks++;
    window.scrollTo(0, document.body.scrollHeight);
    var items = collect();
    if (items.length === lastCount) stable++; else stable = 0;
    lastCount = items.length;
    if (stable >= STABLE_NEEDED || ticks >= MAX_TICKS){
      window.__mjCrawling = false;
      try { AndroidCrawler.onResult(JSON.stringify(items)); } catch(e){}
      return;
    }
    setTimeout(tick, 700);
  }
  setTimeout(tick, 1000);
})();
"""
    }
}
