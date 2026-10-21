package com.taxidriver.launcher.testfixtures

import com.taxidriver.launcher.data.update.InstallConfirmation
import com.taxidriver.launcher.data.update.InstallVerdicts
import com.taxidriver.launcher.data.update.UpdateFailure

/** One verdict [FakeInstallVerdicts] received. */
internal sealed interface ReceivedVerdict {
    data class ConfirmationRequested(
        val sessionId: Int,
        val confirmation: InstallConfirmation,
    ) : ReceivedVerdict

    data class Cancelled(
        val sessionId: Int,
    ) : ReceivedVerdict

    data class Failed(
        val sessionId: Int,
        val reason: UpdateFailure,
    ) : ReceivedVerdict
}

/** Records every verdict it receives, in order. */
internal class FakeInstallVerdicts : InstallVerdicts {
    val received = mutableListOf<ReceivedVerdict>()

    override fun onConfirmationRequested(
        sessionId: Int,
        confirmation: InstallConfirmation,
    ) {
        received += ReceivedVerdict.ConfirmationRequested(sessionId, confirmation)
    }

    override fun onInstallCancelled(sessionId: Int) {
        received += ReceivedVerdict.Cancelled(sessionId)
    }

    override fun onInstallFailed(
        sessionId: Int,
        reason: UpdateFailure,
    ) {
        received += ReceivedVerdict.Failed(sessionId, reason)
    }
}
