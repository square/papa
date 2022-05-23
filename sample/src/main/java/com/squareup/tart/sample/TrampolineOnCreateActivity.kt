package com.squareup.tart.sample

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class TrampolineOnCreateActivity : Activity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    finish()
    startActivity(Intent(this, MainActivity::class.java))
  }
}