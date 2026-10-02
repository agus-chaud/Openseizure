package com.seizureguard.phone.bridge

/**
 * Transport-contract version of THIS companion build (Batch 8, T8.2).
 * Must equal the watch's `WearDataLayerManager.TRANSPORT_CONTRACT_VERSION` for this contract. Bump both
 * together, in the same change, whenever the watch-to-companion message contract changes incompatibly.
 */
const val COMPANION_CONTRACT_VERSION = 1

/** Result of comparing the watch's advertised contract version with the companion's. */
enum class VersionCompatibility {
    /** Same version number on both sides. */
    MATCH,

    /** The watch advertised a different version number. */
    MISMATCH,

    /** The watch advertised no usable version (an app older than Batch 8a, or an invalid value). */
    MISSING;

    /** Both [MISMATCH] and [MISSING] are recorded as a version incompatibility. */
    val isIncompatible: Boolean get() = this != MATCH
}

object ContractVersionCheck {
    /** Pure comparison; [watchVersion] null means the watch did not advertise a valid version. */
    fun compare(watchVersion: Int?, companionVersion: Int): VersionCompatibility = when (watchVersion) {
        null -> VersionCompatibility.MISSING
        companionVersion -> VersionCompatibility.MATCH
        else -> VersionCompatibility.MISMATCH
    }
}

/**
 * Where the passive "versions differ" notice is shown (Batch 8c). Implementations must be silent (no sound,
 * vibration or heads-up, DEC-057), must not throw, and are only called on a transition.
 */
interface VersionNoticeSink {
    /** Show (or re-word) the notice for an incompatible [result] ([VersionCompatibility.isIncompatible]). */
    fun show(result: VersionCompatibility)

    fun clear()

    companion object {
        val NONE = object : VersionNoticeSink {
            override fun show(result: VersionCompatibility) = Unit
            override fun clear() = Unit
        }
    }
}

/**
 * Records the contract-version result of every real watch `/osd/settings` message in the fault log and keeps the
 * silent notice in step with the open period. Recording and surfacing only: it never touches [BridgeFault], the
 * alarm relay or forwarding to OSD, because losing seizure detection is worse than a possibly-mismatched contract.
 * Never throws.
 */
class VersionMismatchRecorder(
    private val faultLog: FaultLog,
    private val companionVersion: Int = COMPANION_CONTRACT_VERSION,
    private val notice: VersionNoticeSink = VersionNoticeSink.NONE,
) {
    private var shown: VersionCompatibility? = null // guarded by this: the incompatible result currently on screen

    @Synchronized
    fun onWatchSettings(settings: WatchSettings): VersionCompatibility {
        val result = ContractVersionCheck.compare(settings.contractVersion, companionVersion)
        runCatching { faultLog.onVersionCompatibility(result) }
        runCatching { updateNotice(result) }
        return result
    }

    /**
     * On service start: re-show the notice if a version period is still open (it survives restarts, R10-F3), and
     * clear any notice a dead process left behind when none is open.
     */
    @Synchronized
    fun restore() {
        runCatching {
            val open = faultLog.openVersionResult()
            if (open != null) notice.show(open) else notice.clear()
            shown = open
        }
    }

    private fun updateNotice(result: VersionCompatibility) {
        if (result.isIncompatible) {
            if (shown != result) { notice.show(result); shown = result }
        } else {
            notice.clear() // idempotent; also removes a notice this instance never saw
            shown = null
        }
    }
}
