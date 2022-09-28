package com.example.papa

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import papa.AppState
import papa.Interaction
import papa.InteractionEventReceiver
import papa.InteractionLatencyReporter
import papa.InteractionRuleClient
import papa.InteractionTrigger
import java.util.Date
import kotlin.time.Duration.Companion.milliseconds

class MainActivity : AppCompatActivity() {

  class OnClick(val element: String)

  class Clicked(override val description: String) : Interaction

  val client = InteractionRuleClient<OnClick, Any>().apply {
    addInteractionRule<Clicked> {
      onEvent<OnClick> { event ->
        startInteraction(Clicked(event.element)).finishOnFrameRendered { result ->
          Log.d("MainActivity", "$result")
        }
      }
    }
    addInteractionRule<Clicked> {
      onEvent<OnClick> { event ->
        startInteraction(Clicked(event.element), onCancel = {
          Log.d("MainActivity", "canceled $it")
        }, cancelTimeout = 2000.milliseconds)
      }
    }
  } as InteractionEventReceiver<OnClick>

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
    InteractionLatencyReporter.reportImmediateInteraction(
      trigger = InteractionTrigger.InputEvent,
      interaction = UpdateText,
      stateBeforeInteraction = AppState.value(previousText),
      stateAfterInteraction = AppState.value(newText)
    )
    client.sendEvent(OnClick(element))

    // val stateHolder = PerformanceMetricsState.getForHierarchy(textView).state!!
    // stateHolder.addState("textview", "updated at $date")
  }

  object UpdateText : Interaction
}