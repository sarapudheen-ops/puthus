package com.bod3.app.catalog

/** Pins native write-oracle reviews; empty until an exact write operation is reviewed. */
object WriteOracleProof {
    // Never accept a marker/hash supplied by a pack itself. Add entries only with
    // checked-in oracle evidence for the exact operation class.
    private val reviewed: Map<String, String> = emptyMap()
    fun verified(marker: String?, reviewHash: String?): Boolean =
        marker != null && marker.isNotBlank() && reviewHash != null && reviewed[marker] == reviewHash
}
