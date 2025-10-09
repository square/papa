package papa.test.utilities

import android.app.Activity
import android.os.Bundle
import com.squareup.papa.test.R

class TestActivity : Activity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.test)
  }
}
