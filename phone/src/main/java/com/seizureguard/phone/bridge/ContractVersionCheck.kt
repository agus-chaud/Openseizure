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
 * Records the contract-version result of every real watch `/osd/settings` message in the fault log.
 * Recording only: it never touches [BridgeFault], the alarm relay or forwarding to OSD, because losing
 * seizure detection is worse than a possibly-mismatched contract. Never throws.
 */
class VersionMismatchRecorder(
    private val faultLog: FaultLog,
    private val companionVersion: Int = COMPANION_CONTRACT_VERSION,
) {
    fun onWatchSettings(settings: WatchSettings): VersionCompatibility {
        val result = ContractVersionCheck.compare(settings.contractVersion, companionVersion)
        runCatching { faultLog.onVersionCompatibility(result) }
        return result
    }
}
