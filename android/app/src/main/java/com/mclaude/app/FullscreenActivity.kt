package com.mclaude.app

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import java.io.File

/** 갤러리에서 사진을 누르면 전체화면으로 크게 보여주는 화면 (핀치/더블탭 확대, 탭하면 닫기) */
class FullscreenActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fullscreen)

        val path = intent.getStringExtra("file")
        if (path.isNullOrEmpty()) {
            finish()
            return
        }
        val file = File(path)
        if (!file.exists()) {
            finish()
            return
        }

        val full = findViewById<ZoomImageView>(R.id.full)
        full.onSingleTap = { finish() }
        // 사진 바깥(검은 영역)을 눌러도 닫기
        findViewById<View>(R.id.root).setOnClickListener { finish() }

        Glide.with(this)
            .load(file)
            .into(full)
    }
}
