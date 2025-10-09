package com.example.papa

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.example.papa.ExampleApplication.Companion.interactionEventSink

class TouchLagActivity : AppCompatActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    setContentView(R.layout.touch_lag)

    findViewById<View>(R.id.no_lag).setOnClickListener {
      interactionEventSink.sendEvent(OnTouchLagClick)
      updateContainerBackgroundColor()
    }
    findViewById<View>(R.id.lag_100_ms).setOnClickListener {
      interactionEventSink.sendEvent(OnTouchLagClick)

      Thread.sleep(100)
      updateContainerBackgroundColor()
    }
    findViewById<View>(R.id.lag_300_ms).setOnClickListener {
      interactionEventSink.sendEvent(OnTouchLagClick)

      Thread.sleep(300)
      updateContainerBackgroundColor()
    }
    findViewById<View>(R.id.lag_700_ms).setOnClickListener {
      interactionEventSink.sendEvent(OnTouchLagClick)
      Thread.sleep(700)
      updateContainerBackgroundColor()
    }
  }

  private fun updateContainerBackgroundColor() {
    val cover = findViewById<View>(R.id.cover)
    cover.visibility = View.VISIBLE
    cover.animate()
      .alpha(0f)
      .setDuration(200)
      .setStartDelay(800)
      .setListener(object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
          cover.visibility = View.GONE
          cover.alpha = 1f
        }
      })
      .start()
  }
}
