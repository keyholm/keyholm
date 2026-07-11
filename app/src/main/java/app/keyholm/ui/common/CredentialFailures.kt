package app.keyholm.ui.common

import android.app.Activity
import android.content.Intent
import androidx.fragment.app.FragmentActivity

internal fun FragmentActivity.finishWith(setException: (Intent) -> Unit) {
    val result = Intent().also(setException)
    setResult(Activity.RESULT_OK, result)
    finish()
}
