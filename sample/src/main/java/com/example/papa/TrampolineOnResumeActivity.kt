package com.example.papa

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper

class TrampolineOnResumeActivity : Activity() {

  override fun onResume() {
    super.onResume()
    // Posting makes for a lousy trampoline where some frames from this activity will render.
    Handler(Looper.getMainLooper()).post {
      finish()
      startActivity(Intent(this, MainActivity::class.java))
    }
  }
}
