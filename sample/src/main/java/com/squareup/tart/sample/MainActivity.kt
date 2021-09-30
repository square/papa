package com.squareup.tart.sample

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    setContentView(R.layout.main)

    findViewById<View>(R.id.freeze_ui).setOnClickListener {
      Handler(Looper.getMainLooper()).postDelayed({
        Thread.sleep(5000)
      }, 200)
    }
  }
}