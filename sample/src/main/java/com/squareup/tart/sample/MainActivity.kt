package com.squareup.tart.sample

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import tart.AppState
import tart.Interaction
import tart.InteractionLatencyReporter
import tart.InteractionTrigger
import java.util.Date

class MainActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    setContentView(R.layout.main)

    findViewById<View>(R.id.freeze_ui).setOnClickListener {
      Handler(Looper.getMainLooper()).postDelayed({
        Thread.sleep(5000)
      }, 200)
    }

    findViewById<View>(R.id.update_button).setOnClickListener {
      trackInteraction("button")
    }

    val listView = findViewById<ListView>(R.id.list_view)

    val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1).apply {
      addAll((0..100).map { "Item $it" }.toList())
      listView.adapter = this
    }
    listView.setOnItemClickListener { _, _, position, _ ->
      trackInteraction(adapter.getItem(position)!!)
    }
  }

  private fun trackInteraction(element: String) {
    val textView = findViewById<TextView>(R.id.updated_textview)
    val previousText = textView.text.toString()
    val newText = "Clicked on $element at ${Date()}"
    textView.text = newText
    InteractionLatencyReporter.reportImmediateInteraction(
      trigger = InteractionTrigger.Input,
      interaction = UpdateText,
      stateBeforeInteraction = AppState.value(previousText),
      stateAfterInteraction = AppState.value(newText)
    )
  }

  object UpdateText : Interaction
}