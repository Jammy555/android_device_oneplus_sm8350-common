/*
 * Copyright (C) 2025 Lunaris Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package org.lineageos.device.DeviceSettings.network

import android.telephony.AccessNetworkConstants

/** One RF band row in the picker, with live modem flags. */
class BandEntry(
    val rat: Int,
    val bandNum: Int,
    val label: String,
    val freqHint: String,
    val isHeader: Boolean = false,
) {
    var checked = false
    /** modem currently using this band */
    var isActive = false
    /** Primary Serving Cell */
    var isPCell = false
    /** Secondary Serving Cell */
    var isSCell = false
    var isExpanded = true

    val key: String get() = "$rat:$bandNum"

    fun copyFlags(): BandEntry = BandEntry(rat, bandNum, label, freqHint, isHeader).also {
        it.checked = checked
        it.isActive = isActive
        it.isPCell = isPCell
        it.isSCell = isSCell
    }
}

/** RF band catalog for OnePlus 9 (SM8350 / X60 modem). */
object BandCatalog {

    const val SECTION_HEADER = -1

    private val NGRAN = AccessNetworkConstants.AccessNetworkType.NGRAN
    private val EUTRAN = AccessNetworkConstants.AccessNetworkType.EUTRAN
    private val UTRAN = AccessNetworkConstants.AccessNetworkType.UTRAN
    private val GERAN = AccessNetworkConstants.AccessNetworkType.GERAN

    @JvmStatic
    fun buildAll(): MutableList<BandEntry> {
        val list = ArrayList<BandEntry>()
        // 5G NR
        list += section("▼ 5G NR Bands")
        list += nr(AccessNetworkConstants.NgranBands.BAND_1, "n1", "2100 MHz FDD")
        list += nr(AccessNetworkConstants.NgranBands.BAND_2, "n2", "1900 MHz FDD")
        list += nr(AccessNetworkConstants.NgranBands.BAND_3, "n3", "1800 MHz FDD")
        list += nr(AccessNetworkConstants.NgranBands.BAND_5, "n5", "850 MHz FDD")
        list += nr(AccessNetworkConstants.NgranBands.BAND_7, "n7", "2600 MHz FDD")
        list += nr(AccessNetworkConstants.NgranBands.BAND_8, "n8", "900 MHz FDD")
        list += nr(AccessNetworkConstants.NgranBands.BAND_20, "n20", "800 MHz FDD")
        list += nr(AccessNetworkConstants.NgranBands.BAND_25, "n25", "1900 MHz FDD")
        list += nr(AccessNetworkConstants.NgranBands.BAND_28, "n28", "700 MHz FDD")
        list += nr(AccessNetworkConstants.NgranBands.BAND_38, "n38", "2600 MHz TDD")
        list += nr(AccessNetworkConstants.NgranBands.BAND_40, "n40", "2300 MHz TDD")
        list += nr(AccessNetworkConstants.NgranBands.BAND_41, "n41", "2500 MHz TDD")
        list += nr(AccessNetworkConstants.NgranBands.BAND_48, "n48", "3550 MHz TDD")
        list += nr(AccessNetworkConstants.NgranBands.BAND_66, "n66", "1700 MHz FDD")
        list += nr(AccessNetworkConstants.NgranBands.BAND_71, "n71", "600 MHz FDD")
        list += nr(AccessNetworkConstants.NgranBands.BAND_77, "n77", "3700 MHz TDD")
        list += nr(AccessNetworkConstants.NgranBands.BAND_78, "n78", "3500 MHz TDD")

        // LTE 4G
        list += section("▼ 4G LTE Bands")
        list += lte(AccessNetworkConstants.EutranBand.BAND_1, "B1", "2100 MHz FDD")
        list += lte(AccessNetworkConstants.EutranBand.BAND_2, "B2", "1900 MHz FDD")
        list += lte(AccessNetworkConstants.EutranBand.BAND_3, "B3", "1800 MHz FDD")
        list += lte(AccessNetworkConstants.EutranBand.BAND_4, "B4", "1700 MHz FDD")
        list += lte(AccessNetworkConstants.EutranBand.BAND_5, "B5", "850 MHz FDD")
        list += lte(AccessNetworkConstants.EutranBand.BAND_7, "B7", "2600 MHz FDD")
        list += lte(AccessNetworkConstants.EutranBand.BAND_8, "B8", "900 MHz FDD")
        list += lte(AccessNetworkConstants.EutranBand.BAND_12, "B12", "700 MHz FDD")
        list += lte(AccessNetworkConstants.EutranBand.BAND_13, "B13", "700 MHz FDD")
        list += lte(AccessNetworkConstants.EutranBand.BAND_17, "B17", "700 MHz FDD")
        list += lte(AccessNetworkConstants.EutranBand.BAND_18, "B18", "850 MHz FDD")
        list += lte(AccessNetworkConstants.EutranBand.BAND_19, "B19", "850 MHz FDD")
        list += lte(AccessNetworkConstants.EutranBand.BAND_20, "B20", "800 MHz FDD")
        list += lte(AccessNetworkConstants.EutranBand.BAND_25, "B25", "1900 MHz FDD")
        list += lte(AccessNetworkConstants.EutranBand.BAND_26, "B26", "850 MHz FDD")
        list += lte(AccessNetworkConstants.EutranBand.BAND_28, "B28", "700 MHz FDD")
        list += lte(AccessNetworkConstants.EutranBand.BAND_30, "B30", "2300 MHz FDD")
        list += lte(AccessNetworkConstants.EutranBand.BAND_34, "B34", "2010 MHz TDD")
        list += lte(AccessNetworkConstants.EutranBand.BAND_38, "B38", "2600 MHz TDD")
        list += lte(AccessNetworkConstants.EutranBand.BAND_39, "B39", "1900 MHz TDD")
        list += lte(AccessNetworkConstants.EutranBand.BAND_40, "B40", "2300 MHz TDD")
        list += lte(AccessNetworkConstants.EutranBand.BAND_41, "B41", "2500 MHz TDD")
        list += lte(AccessNetworkConstants.EutranBand.BAND_46, "B46", "5150 MHz TDD")
        list += lte(AccessNetworkConstants.EutranBand.BAND_48, "B48", "3550 MHz TDD")
        list += lte(AccessNetworkConstants.EutranBand.BAND_66, "B66", "1700 MHz FDD")
        list += lte(AccessNetworkConstants.EutranBand.BAND_71, "B71", "600 MHz FDD")

        // WCDMA 3G
        list += section("▼ 3G WCDMA Bands")
        list += wcdma(AccessNetworkConstants.UtranBand.BAND_1, "B1", "2100 MHz")
        list += wcdma(AccessNetworkConstants.UtranBand.BAND_2, "B2", "1900 MHz")
        list += wcdma(AccessNetworkConstants.UtranBand.BAND_4, "B4", "1700 MHz")
        list += wcdma(AccessNetworkConstants.UtranBand.BAND_5, "B5", "850 MHz")
        list += wcdma(AccessNetworkConstants.UtranBand.BAND_8, "B8", "900 MHz")
        list += wcdma(AccessNetworkConstants.UtranBand.BAND_9, "B9", "1700 MHz")
        list += wcdma(AccessNetworkConstants.UtranBand.BAND_19, "B19", "850 MHz")

        // GSM 2G
        list += section("▼ 2G GSM Bands")
        list += gsm(AccessNetworkConstants.GeranBand.BAND_PCS1900, "B2 / 1900", "1900 MHz")
        list += gsm(AccessNetworkConstants.GeranBand.BAND_DCS1800, "B3 / 1800", "1800 MHz")
        list += gsm(AccessNetworkConstants.GeranBand.BAND_850, "B5 / 850", "850 MHz")
        list += gsm(AccessNetworkConstants.GeranBand.BAND_E900, "B8 / 900", "900 MHz")
        return list
    }

    private fun section(title: String) = BandEntry(SECTION_HEADER, SECTION_HEADER, title, "", true)
    private fun nr(band: Int, label: String, freq: String) = BandEntry(NGRAN, band, "5G $label", freq)
    private fun lte(band: Int, label: String, freq: String) = BandEntry(EUTRAN, band, "4G $label", freq)
    private fun wcdma(band: Int, label: String, freq: String) = BandEntry(UTRAN, band, "3G $label", freq)
    private fun gsm(band: Int, label: String, freq: String) = BandEntry(GERAN, band, "2G $label", freq)
}
