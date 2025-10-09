package com.example.papa

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import com.google.android.material.R
import com.google.android.material.slider.Slider

class LagSlider(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
  Slider(context, attrs, defStyleAttr) {

  val getValueOfTouchPositionAbsolute by lazy {
    val method = Class.forName("com.google.android.material.slider.BaseSlider")
      .getDeclaredMethod("getValueOfTouchPositionAbsolute").apply {
        isAccessible = true
      };
    {
      method.invoke(this) as Float
    }
  }

  init {
    setLabelFormatter { value ->
      "Lag: ${value.toInt()} ms"
    }
  }

  constructor(context: Context) : this(context, null)

  constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, R.attr.sliderStyle)

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    Thread.sleep(getValueOfTouchPositionAbsolute().toLong())
  }
}
