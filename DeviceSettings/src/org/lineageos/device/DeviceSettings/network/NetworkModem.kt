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

import android.content.Context
import android.content.SharedPreferences
import android.os.IBinder
import android.os.ServiceManager
import android.os.SystemProperties
import android.telephony.AccessNetworkConstants
import android.telephony.PhysicalChannelConfig
import android.telephony.RadioAccessSpecifier
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import java.io.File
import java.util.Locale
import vendor.oplus.hardware.radio.IOplusRadio

/** Preference file + keys shared by the screen and the boot restore. Unchanged from the old fragment. */
object NetworkPrefs {
    const val NAME = "band_lock_prefs"
    const val KEY_BANDS = "selected_bands_" // + subId
    const val KEY_NR_MODE = "nr_mode_sub_" // + subId
    const val KEY_RAT_MODE = "rat_mode_sub_" // + subId
    const val KEY_CARRIER_PRESET = "carrier_preset_sub_" // + subId
    const val KEY_CUSTOM_RECOMMENDED = "custom_recommended_sub_" // + subId
    const val KEY_CUSTOM_BATTERY_SAVER = "custom_battery_saver_sub_" // + subId
    const val KEY_NON_INDIA_POPUP = "first_time_non_india_popup_shown"
    const val KEY_DYNAMIC_COLORS = "use_dynamic_colors"

    fun of(context: Context): SharedPreferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}

/** Modem-facing helpers with no UI: RAT bitmasks, OPlus radio HAL, band maths, presets. */
object NetworkModem {

    private const val TAG = "NetworkBands"

    const val OPLUS_NR_MODE_NSA_ONLY = 1
    const val OPLUS_NR_MODE_SA_ONLY = 2
    const val OPLUS_NR_MODE_SA_PRE = 3

    private const val MASK_2G = TelephonyManager.NETWORK_TYPE_BITMASK_GSM or
        TelephonyManager.NETWORK_TYPE_BITMASK_GPRS or TelephonyManager.NETWORK_TYPE_BITMASK_EDGE
    private const val MASK_3G = TelephonyManager.NETWORK_TYPE_BITMASK_UMTS or
        TelephonyManager.NETWORK_TYPE_BITMASK_HSDPA or TelephonyManager.NETWORK_TYPE_BITMASK_HSUPA or
        TelephonyManager.NETWORK_TYPE_BITMASK_HSPA or TelephonyManager.NETWORK_TYPE_BITMASK_HSPAP
    @Suppress("DEPRECATION") // Decode legacy saved LTE-CA masks as well as current LTE masks.
    private const val MASK_4G = TelephonyManager.NETWORK_TYPE_BITMASK_LTE or TelephonyManager.NETWORK_TYPE_BITMASK_LTE_CA
    private const val MASK_5G = TelephonyManager.NETWORK_TYPE_BITMASK_NR
    const val MASK_ALL = MASK_2G or MASK_3G or MASK_4G or MASK_5G

    data class Rats(val g2: Boolean, val g3: Boolean, val g4: Boolean, val g5: Boolean) {
        fun toMask(): Long {
            var mask = 0L
            if (g2) mask = mask or MASK_2G
            if (g3) mask = mask or MASK_3G
            if (g4) mask = mask or MASK_4G
            if (g5) mask = mask or MASK_5G
            return mask
        }

        /** Index into the quick-preset list (0 = custom combination). */
        fun presetIndex(): Int = when {
            g5 && g4 && !g3 && !g2 -> 1
            !g5 && g4 && !g3 && !g2 -> 2
            g5 && !g4 && !g3 && !g2 -> 3
            !g5 && !g4 && g3 && g2 -> 4
            !g5 && !g4 && !g3 && g2 -> 5
            g5 && g4 && g3 && g2 -> 6
            else -> 0
        }
    }

    fun ratsFromMask(bitmask: Long) = Rats(
        g2 = bitmask and MASK_2G != 0L,
        g3 = bitmask and MASK_3G != 0L,
        g4 = bitmask and MASK_4G != 0L,
        g5 = bitmask and MASK_5G != 0L,
    )

    fun ratsForPreset(index: Int): Rats? = when (index) {
        1 -> Rats(false, false, true, true)
        2 -> Rats(false, false, true, false)
        3 -> Rats(false, false, false, true)
        4 -> Rats(true, true, false, false)
        5 -> Rats(true, false, false, false)
        6 -> Rats(true, true, true, true)
        else -> null
    }

    fun networkTypeToAccessNetworkType(networkType: Int): Int = when (networkType) {
        TelephonyManager.NETWORK_TYPE_NR -> AccessNetworkConstants.AccessNetworkType.NGRAN
        TelephonyManager.NETWORK_TYPE_LTE, TelephonyManager.NETWORK_TYPE_LTE_CA -> AccessNetworkConstants.AccessNetworkType.EUTRAN
        TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_TD_SCDMA ->
            AccessNetworkConstants.AccessNetworkType.UTRAN
        TelephonyManager.NETWORK_TYPE_GSM, TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE ->
            AccessNetworkConstants.AccessNetworkType.GERAN
        else -> AccessNetworkConstants.AccessNetworkType.UNKNOWN
    }

    fun isPhysicalChannelPrimary(config: PhysicalChannelConfig?): Boolean {
        if (config == null) return false
        return try {
            val status = config.javaClass.getMethod("getConnectionStatus").invoke(config) as Int
            status == 1 // PHYSICAL_CHANNEL_CONFIG_CONNECTION_PRIMARY
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------ OPlus radio HAL

    fun setOplusNrMode(slotId: Int, mode: Int) {
        try {
            val binder: IBinder? = ServiceManager.getService("vendor.oplus.hardware.radio.IOplusRadio/slot$slotId")
            if (binder != null) {
                val service = IOplusRadio.Stub.asInterface(binder)
                if (service != null) {
                    service.setNrMode(0, mode)
                    return
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "AIDL IOplusRadio setNrMode unavailable: ${t.message}")
        }
        try {
            val clazz = Class.forName("vendor.oplus.hardware.radio.V2_0.IOplusRadio")
            val service = clazz.getMethod("getService", String::class.java).invoke(null, "slot$slotId")
            if (service != null) {
                clazz.getMethod("setNrMode", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .invoke(service, 0, mode)
            }
        } catch (e: Exception) {
            Log.w(TAG, "OplusRadio HAL setNrMode failed: ${e.message}")
        }
    }

    fun nrModeToOplus(progress: Int): Int = when (progress) {
        0 -> OPLUS_NR_MODE_NSA_ONLY
        2 -> OPLUS_NR_MODE_SA_ONLY
        else -> OPLUS_NR_MODE_SA_PRE
    }

    fun setOplusVoNrEnabled(slotId: Int, enabled: Boolean) {
        Log.i(TAG, "setOplusVoNrEnabled: Toggling Vo5G/VoNR for slot=$slotId, enabled=$enabled")
        try {
            SystemProperties.set("persist.sys.vonr_enable", if (enabled) "true" else "false")
            SystemProperties.set("persist.vendor.radio.vonr_enabled", if (enabled) "1" else "0")
            val binder = ServiceManager.getService("vendor.oplus.hardware.radio.IOplusRadio/slot$slotId")
            if (binder != null) {
                IOplusRadio.Stub.asInterface(binder)?.setVoNrEnabled(0, enabled)
            }
        } catch (e: Exception) {
            Log.w(TAG, "setOplusVoNrEnabled failed: ${e.message}")
        }
    }

    fun isVoNrEnabled(): Boolean = SystemProperties.getBoolean(
        "persist.sys.vonr_enable", SystemProperties.getBoolean("persist.vendor.radio.vonr_enabled", true)
    )

    // ------------------------------------------------------------------ band maths

    fun earfcnToLteBand(earfcn: Int): Int = when (earfcn) {
        in 0..599 -> 1
        in 600..1199 -> 2
        in 1200..1949 -> 3
        in 1950..2399 -> 4
        in 2400..2649 -> 5
        in 2750..3449 -> 7
        in 3450..3799 -> 8
        in 5010..5179 -> 12
        in 5180..5279 -> 13
        in 5730..5849 -> 17
        in 5850..5999 -> 18
        in 6000..6149 -> 19
        in 6150..6449 -> 20
        in 8040..8689 -> 25
        in 8690..9039 -> 26
        in 9210..9659 -> 28
        in 9770..9869 -> 30
        in 36200..36349 -> 34
        in 37750..38249 -> 38
        in 38250..38649 -> 39
        in 38650..39649 -> 40
        in 39650..41589 -> 41
        in 46790..54539 -> 46
        in 55240..56739 -> 48
        in 66436..67335 -> 66
        in 68586..68935 -> 71
        else -> 0
    }

    /** Several NR bands share channel ranges, so this returns every match, like before. */
    fun nrarfcnToNrBands(arfcn: Int): List<Int> {
        val bands = ArrayList<Int>()
        if (arfcn in 422000..434000) bands.add(1)
        if (arfcn in 386000..398000) bands.add(2)
        if (arfcn in 361000..376000) bands.add(3)
        if (arfcn in 173800..178800) bands.add(5)
        if (arfcn in 524000..538000) bands.add(7)
        if (arfcn in 185000..192000) bands.add(8)
        if (arfcn in 158200..164200) bands.add(20)
        if (arfcn in 386000..399000) bands.add(25)
        if (arfcn in 151600..160600) bands.add(28)
        if (arfcn in 514000..524000) bands.add(38)
        if (arfcn in 460000..480000) bands.add(40)
        if (arfcn in 499200..537999) bands.add(41)
        if (arfcn in 636667..646666) bands.add(48)
        if (arfcn in 422000..440000) bands.add(66)
        if (arfcn in 123400..130400) bands.add(71)
        if (arfcn in 620000..680000) bands.add(77)
        if (arfcn in 620000..653333) bands.add(78)
        return bands
    }

    fun readModemTemperature(): String {
        try {
            for (i in 0..40) {
                val typeFile = File("/sys/class/thermal/thermal_zone$i/type")
                if (typeFile.exists()) {
                    val type = typeFile.readText().trim().lowercase(Locale.ROOT)
                    if (type.contains("modem") || type.contains("pa") || type.contains("qcom")) {
                        val tempFile = File("/sys/class/thermal/thermal_zone$i/temp")
                        if (tempFile.exists()) {
                            var value = tempFile.readText().trim().toFloat()
                            if (value > 1000) value /= 1000.0f
                            if (value > 0 && value < 100) return String.format(Locale.US, "%.1f°C (%s)", value, type)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return "--"
    }

    fun isNonIndiaRegion(tm: TelephonyManager?): Boolean {
        try {
            // modem RF version 13 = India
            if (SystemProperties.get("ro.boot.rf_version", "") == "13") return false
            val model = SystemProperties.get("ro.product.product.model", "")
            if (model.equals("LE2111", true) || model.equals("LE2121", true) ||
                model.equals("RMX3360", true) || model.equals("MT2111", true)
            ) return false
            val simOperator = tm?.simOperator
            if (simOperator != null && (simOperator.startsWith("404") || simOperator.startsWith("405"))) return false
            return true
        } catch (e: Exception) {
            return false
        }
    }

    // ------------------------------------------------------------------ specifiers

    fun buildSpecifiers(entries: List<BandEntry>, tm: TelephonyManager): List<RadioAccessSpecifier> {
        val nrBands = ArrayList<Int>()
        val lteBands = ArrayList<Int>()
        val wcdmaBands = ArrayList<Int>()
        val gsmBands = ArrayList<Int>()
        for (e in entries) {
            if (e.isHeader || !e.checked) continue
            when (e.rat) {
                AccessNetworkConstants.AccessNetworkType.NGRAN -> nrBands.add(e.bandNum)
                AccessNetworkConstants.AccessNetworkType.EUTRAN -> lteBands.add(e.bandNum)
                AccessNetworkConstants.AccessNetworkType.UTRAN -> wcdmaBands.add(e.bandNum)
                AccessNetworkConstants.AccessNetworkType.GERAN -> gsmBands.add(e.bandNum)
            }
        }
        try {
            val bitmask = tm.getAllowedNetworkTypesForReason(TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER)
            val is5gEnabled = bitmask and TelephonyManager.NETWORK_TYPE_BITMASK_NR != 0L
            if (!is5gEnabled && nrBands.isNotEmpty()) nrBands.clear()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read system 5G toggle state: ${e.message}")
        }

        val sortedLte = ArrayList<Int>()
        for (b in intArrayOf(3, 40, 1, 5, 8, 28)) if (lteBands.contains(b)) sortedLte.add(b)
        for (b in lteBands) if (!sortedLte.contains(b)) sortedLte.add(b)

        val specifiers = ArrayList<RadioAccessSpecifier>()
        if (nrBands.isNotEmpty()) specifiers.add(RadioAccessSpecifier(AccessNetworkConstants.AccessNetworkType.NGRAN, nrBands.toIntArray(), null))
        if (sortedLte.isNotEmpty()) specifiers.add(RadioAccessSpecifier(AccessNetworkConstants.AccessNetworkType.EUTRAN, sortedLte.toIntArray(), null))
        if (wcdmaBands.isNotEmpty()) specifiers.add(RadioAccessSpecifier(AccessNetworkConstants.AccessNetworkType.UTRAN, wcdmaBands.toIntArray(), null))
        if (gsmBands.isNotEmpty()) specifiers.add(RadioAccessSpecifier(AccessNetworkConstants.AccessNetworkType.GERAN, gsmBands.toIntArray(), null))
        return specifiers
    }

    fun buildAllBandsSpecifiers(tm: TelephonyManager?): List<RadioAccessSpecifier> {
        val simOperator = tm?.simOperator ?: ""
        val rfVer = SystemProperties.get("ro.boot.rf_version", "0")
        val nrBands: IntArray
        val lteBands: IntArray
        if (simOperator.startsWith("310") || simOperator.startsWith("311") || simOperator.startsWith("312") ||
            rfVer == "12" || rfVer == "22"
        ) {
            // US / North America (Sub-6GHz + mmWave FR2: n258, n260, n261)
            nrBands = intArrayOf(2, 5, 12, 25, 41, 66, 71, 77, 78, 258, 260, 261)
            lteBands = intArrayOf(2, 4, 5, 12, 13, 25, 26, 41, 66, 71)
        } else if (listOf("204", "208", "222", "234", "262").any { simOperator.startsWith(it) } || rfVer == "21") {
            // EU
            nrBands = intArrayOf(1, 3, 7, 8, 20, 28, 77, 78)
            lteBands = intArrayOf(1, 3, 7, 8, 20, 28, 38, 40)
        } else {
            // India & global fallback
            nrBands = intArrayOf(1, 3, 5, 8, 28, 78)
            lteBands = intArrayOf(1, 3, 5, 8, 40, 41)
        }
        return listOf(
            RadioAccessSpecifier(AccessNetworkConstants.AccessNetworkType.NGRAN, nrBands, null),
            RadioAccessSpecifier(AccessNetworkConstants.AccessNetworkType.EUTRAN, lteBands, null),
        )
    }
}

/** Carrier-optimised band sets. Rule order matters (first match wins), exactly as before. */
object CarrierPresets {

    private val L = AccessNetworkConstants.AccessNetworkType.EUTRAN
    private val N = AccessNetworkConstants.AccessNetworkType.NGRAN

    private class Rule(val match: (String) -> Boolean, val bands: List<Pair<Int, Int>>)

    private fun lte(vararg b: Int) = b.map { L to it }
    private fun nr(vararg b: Int) = b.map { N to it }

    private val RECOMMENDED = listOf(
        Rule({ it.contains("jio") }, lte(3, 5, 40) + nr(28, 78)),
        Rule({ it.contains("airtel") }, lte(1, 3, 8, 40) + nr(1, 3, 28, 40, 78)),
        Rule({ it.contains("vi") || it.contains("vodafone") || it.contains("idea") }, lte(1, 3, 8, 40) + nr(78)),
        Rule({ it.contains("bsnl") }, lte(1, 3, 5, 28) + nr(28, 78)),
        Rule({ it.contains("t-mobile") || it.contains("tmobile") }, lte(2, 4, 12, 66, 71) + nr(41, 71)),
        Rule({ it.contains("verizon") }, lte(2, 5, 13, 66) + nr(77)),
        Rule({ it.contains("at&t") || it.contains("att") }, lte(2, 5, 12, 14, 30, 66) + nr(5, 77)),
        Rule({ it.contains("china mobile") }, lte(3, 8, 39, 40, 41) + nr(41, 79)),
        Rule({ it.contains("china unicom") }, lte(1, 3) + nr(78)),
        Rule({ it.contains("china telecom") }, lte(1, 3, 5) + nr(78)),
    )

    // Universal: primary global LTE anchors (B1, B3, B5, B7, B8, B20, B28, B40, B66) and 5G (n1, n3, n28, n77, n78)
    private val RECOMMENDED_DEFAULT = lte(1, 3, 5, 7, 8, 20, 28, 40, 66) + nr(1, 3, 28, 77, 78)

    private val BATTERY_SAVER = listOf(
        Rule({ it.contains("jio") }, lte(3, 5)),
        Rule({ it.contains("airtel") || it.contains("vi") || it.contains("vodafone") || it.contains("idea") }, lte(1, 3, 8)),
        Rule({ it.contains("bsnl") }, lte(1, 3, 5, 28)),
        Rule({ it.contains("t-mobile") || it.contains("tmobile") }, lte(12, 71)),
        Rule({ it.contains("verizon") }, lte(5, 13)),
        Rule({ it.contains("at&t") || it.contains("att") }, lte(5, 12)),
    )

    // Universal low-band FDD anchors (B1, B3, B5, B8, B20, B28)
    private val BATTERY_SAVER_DEFAULT = lte(1, 3, 5, 8, 20, 28)

    /** option 1 = Recommended, option 2 = Battery Saver. */
    fun bandsFor(option: Int, carrier: String?): List<Pair<Int, Int>> {
        val c = carrier?.lowercase(Locale.ROOT) ?: ""
        return when (option) {
            1 -> RECOMMENDED.firstOrNull { it.match(c) }?.bands ?: RECOMMENDED_DEFAULT
            2 -> BATTERY_SAVER.firstOrNull { it.match(c) }?.bands ?: BATTERY_SAVER_DEFAULT
            else -> emptyList()
        }
    }
}

/** Boot-time restore of NR mode and RAT mask, called from [org.lineageos.device.DeviceSettings.Startup]. */
object NetworkBandsRestore {
    private const val TAG = "NetworkBands"

    @JvmStatic
    fun restoreNrModeSettings(context: Context?) {
        if (context == null) return
        try {
            val prefs = NetworkPrefs.of(context)
            val sm = context.getSystemService(SubscriptionManager::class.java)
            val tm = context.getSystemService(TelephonyManager::class.java)
            if (sm == null || tm == null) return
            val activeSubs = sm.activeSubscriptionInfoList ?: return

            for (info in activeSubs) {
                val subId = info.subscriptionId
                val slotId = SubscriptionManager.getSlotIndex(subId)
                val subTm = tm.createForSubscriptionId(subId)

                // 1. Restore 5G NR mode
                val savedNrMode = prefs.getInt(NetworkPrefs.KEY_NR_MODE + subId, 1)
                if (SubscriptionManager.isValidSlotIndex(slotId)) {
                    NetworkModem.setOplusNrMode(slotId, NetworkModem.nrModeToOplus(savedNrMode))
                }

                // 2. Restore RAT mode bitmask (e.g. 4G only / 5G+4G auto)
                val savedRatBitmask = prefs.getLong(NetworkPrefs.KEY_RAT_MODE + subId, 0)
                if (savedRatBitmask != 0L) {
                    try {
                        subTm.setAllowedNetworkTypesForReason(TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER, savedRatBitmask)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to restore RAT bitmask on boot for sub $subId: ${e.message}")
                    }
                }

                // 3. Clear transient band lock and preset UI state
                prefs.edit()
                    .remove(NetworkPrefs.KEY_BANDS + subId)
                    .remove(NetworkPrefs.KEY_CARRIER_PRESET + subId)
                    .apply()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore telephony settings on boot: ${e.message}")
        }
    }
}
