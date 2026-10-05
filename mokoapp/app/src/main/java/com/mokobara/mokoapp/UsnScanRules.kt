package com.mokobara.mokoapp

/** Barcode values that are product EAN, not warehouse USN. */
object UsnScanRules {
    /** Mokobara EAN prefix — reject as USN when barcode starts with this. */
    const val EAN_USN_REJECT_PREFIX = "890444450"

    fun looksLikeEanNotUsn(barcode: String): Boolean {
        return barcode.trim().startsWith(EAN_USN_REJECT_PREFIX)
    }
}
