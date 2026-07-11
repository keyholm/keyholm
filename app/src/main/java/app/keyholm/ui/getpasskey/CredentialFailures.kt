package app.keyholm.ui.getpasskey

import android.widget.Toast
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.provider.PendingIntentHandler
import androidx.fragment.app.FragmentActivity
import app.keyholm.ui.common.finishWith

internal fun FragmentActivity.failGetCredential(
    exception: GetCredentialException,
    toastMessage: String? = null,
) {
    toastMessage?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
    finishWith { PendingIntentHandler.setGetCredentialException(it, exception) }
}

internal fun FragmentActivity.cancelGetCredential() =
    finishWith {
        PendingIntentHandler.setGetCredentialException(it, GetCredentialCancellationException())
    }
