package com.example.papa

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.papa.ExampleApplication.Companion.interactionEventReceiver
import java.util.Date

class MainActivity : AppCompatActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.loader)

    Handler(Looper.getMainLooper()).postDelayed({
      doneLoading()
      // Just adding this to see it show up in traces.
      reportFullyDrawn()
    }, 1000)
  }

  private fun doneLoading() {
    setContentView(R.layout.main)

    findViewById<View>(R.id.finish_activity).setOnClickListener {
      finish()
    }

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
      Toast.makeText(this, "tapped", Toast.LENGTH_SHORT).show()
    }
  }

  private fun trackInteraction(element: String) {
    val textView = findViewById<TextView>(R.id.updated_textview)
    val previousText = textView.text.toString()
    val date = Date()
    val newText = "Clicked on $element at $date"
    textView.text = newText
    interactionEventReceiver.sendEvent(OnMainActivityButtonClick(element, previousText, newText))
    // val stateHolder = PerformanceMetricsState.getForHierarchy(textView).state!!
    // stateHolder.addState("textview", "updated at $date")
  }
}