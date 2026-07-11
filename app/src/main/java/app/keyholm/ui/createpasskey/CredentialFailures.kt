package app.keyholm.ui.createpasskey

import android.widget.Toast
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.provider.PendingIntentHandler
import androidx.fragment.app.FragmentActivity
import app.keyholm.ui.common.finishWith

internal fun FragmentActivity.failCreateCredential(
    exception: CreateCredentialException,
    toastMessage: String? = null,
) {
    toastMessage?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
    finishWith { PendingIntentHandler.setCreateCredentialException(it, exception) }
}

internal fun FragmentActivity.cancelCreateCredential() =
    finishWith {
        PendingIntentHandler.setCreateCredentialException(it, CreateCredentialCancellationException())
    }
