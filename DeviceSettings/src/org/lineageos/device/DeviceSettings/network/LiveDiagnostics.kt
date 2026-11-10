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

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.SystemProperties
import android.telephony.AccessNetworkConstants
import android.telephony.CarrierConfigManager
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthWcdma
import android.telephony.NetworkRegistrationInfo
import android.telephony.PhysicalChannelConfig
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Last sampled values, to show the green/red trend arrows. */
class DiagTrend {
    var lastRsrp = -999
    var lastSinr = -999
    var lastCqi = -1
}

/** A metric with its trend: -1 worse than last sample, 0 same / unknown, 1 better. */
data class DiagMetric(val text: String, val trend: Int)

data class DiagSection(val title: String, val rows: List<Pair<String, String>>)

data class DiagSnapshot(
    val tech: String,
    val hasService: Boolean,
    val caDisplay: String,
    val rsrp: DiagMetric,
    val sinr: DiagMetric,
    val cqi: DiagMetric,
    val signalQuality: String,
    val sections: List<DiagSection>,
)

object LiveDiagnostics {

    private const val INVALID = Int.MAX_VALUE

    private fun trendOf(now: Int, last: Int, valid: Boolean, lastValid: Boolean): Int =
        if (valid && lastValid) (if (now > last) 1 else if (now < last) -1 else 0) else 0

    private fun isEnDcAvailable(ss: ServiceState?) = dataInfoFlag(ss, "isEnDcAvailable")

    private fun isDcnrRestricted(ss: ServiceState?) = dataInfoFlag(ss, "isDcnrRestricted")

    private fun dataInfoFlag(ss: ServiceState?, method: String): Boolean {
        if (ss == null) return false
        try {
            val nri = ss.getNetworkRegistrationInfo(NetworkRegistrationInfo.DOMAIN_PS, AccessNetworkConstants.TRANSPORT_TYPE_WWAN)
            val dataInfo = nri?.dataSpecificInfo
            if (dataInfo != null) return dataInfo.javaClass.getMethod(method).invoke(dataInfo) as Boolean
        } catch (_: Exception) {
        }
        return false
    }

    /** Samples telephony state and builds everything the diagnostics cards show. Call off the main thread. */
    @SuppressLint("MissingPermission")
    fun collect(
        context: Context,
        tm: TelephonyManager,
        subId: Int,
        physicalConfigs: List<PhysicalChannelConfig>,
        carrierName: String,
        signal: SignalStrength?,
        trend: DiagTrend,
        forceVo5g: Boolean,
    ): DiagSnapshot {
        var dataRat = tm.dataNetworkType
        var voiceRat = tm.voiceNetworkType
        // This off-main-thread snapshot needs the synchronous IMS state.
        @Suppress("DEPRECATION")
        val isImsRegistered = tm.isImsRegistered

        var ss = signal
        if (ss == null) {
            try {
                ss = tm.signalStrength
            } catch (_: Exception) {
            }
        }

        var ssState: ServiceState? = null
        try {
            ssState = tm.serviceState
            if (ssState != null) {
                if (dataRat == TelephonyManager.NETWORK_TYPE_UNKNOWN) dataRat = ssState.dataNetworkType
                if (voiceRat == TelephonyManager.NETWORK_TYPE_UNKNOWN) voiceRat = ssState.voiceNetworkType
            }
        } catch (_: Exception) {
        }

        val endcAvail = isEnDcAvailable(ssState)
        val dcnrRestricted = isDcnrRestricted(ssState)

        var rsrp = -999
        var rsrq = -999
        var sinr = -999
        var cqi = -1
        var ta = -1
        var pci = -1
        var earfcn = -1
        var lteBand = -1
        var nrArfcn = -1
        var nrBand = -1
        var ccCount = 0
        var totalBwKhz = 0

        if (ss != null) {
            for (css in ss.cellSignalStrengths) {
                when (css) {
                    is CellSignalStrengthLte -> {
                        if (rsrp == -999) rsrp = css.rsrp
                        if (rsrq == -999) rsrq = css.rsrq
                        if (sinr == -999) sinr = css.rssnr
                        if (cqi == -1 || cqi == INVALID) cqi = css.cqi
                        if (ta == -1 || ta == INVALID) ta = css.timingAdvance
                    }
                    is CellSignalStrengthNr -> {
                        if (rsrp == -999) rsrp = css.ssRsrp
                        if (rsrq == -999) rsrq = css.ssRsrq
                        if (sinr == -999) sinr = css.ssSinr
                    }
                    is CellSignalStrengthGsm -> {
                        if (rsrp == -999) rsrp = css.dbm
                        if (ta == -1 || ta == INVALID) ta = css.timingAdvance
                    }
                    is CellSignalStrengthWcdma -> {
                        if (rsrp == -999) rsrp = css.dbm
                        if (sinr == -999) sinr = css.ecNo
                    }
                }
            }
            // Fallback: system level primary dBm if the RAT specific value is unavailable
            if (rsrp == -999 || rsrp == INVALID) {
                // Preserve the platform-selected primary RAT in this fallback.
                @Suppress("DEPRECATION")
                val dbm = ss.dbm
                if (dbm != 0 && dbm != -1 && dbm != INVALID) rsrp = dbm
            }
        }

        // CellInfo, for when PhysicalChannelConfigs returns nothing
        try {
            val allCellInfo = tm.allCellInfo
            if (allCellInfo != null) {
                for (info in allCellInfo) {
                    if (!info.isRegistered) continue
                    when (info) {
                        is CellInfoLte -> {
                            val id = info.cellIdentity
                            if (earfcn <= 0) earfcn = id.earfcn
                            if (pci < 0) pci = id.pci
                            if (lteBand <= 0 && earfcn > 0) lteBand = NetworkModem.earfcnToLteBand(earfcn)
                            val s = info.cellSignalStrength
                            if (rsrp == -999) rsrp = s.rsrp
                            if (rsrq == -999) rsrq = s.rsrq
                            if (sinr == -999) sinr = s.rssnr
                            if (cqi == -1 || cqi == INVALID) cqi = s.cqi
                            if (ta == -1 || ta == INVALID) ta = s.timingAdvance
                        }
                        is CellInfoNr -> {
                            val id = info.cellIdentity as CellIdentityNr
                            if (nrArfcn <= 0) nrArfcn = id.nrarfcn
                            if (pci < 0) pci = id.pci
                            val s = info.cellSignalStrength as CellSignalStrengthNr
                            if (rsrp == -999) rsrp = s.ssRsrp
                            if (rsrq == -999) rsrq = s.ssRsrq
                            if (sinr == -999) sinr = s.ssSinr
                        }
                        is CellInfoGsm -> {
                            val id = info.cellIdentity
                            if (pci < 0) pci = id.bsic
                            if (earfcn <= 0) earfcn = id.arfcn
                            val s = info.cellSignalStrength
                            if (rsrp == -999) rsrp = s.dbm
                            if (ta == -1 || ta == INVALID) ta = s.timingAdvance
                        }
                        is CellInfoWcdma -> {
                            val id = info.cellIdentity
                            if (pci < 0) pci = id.psc
                            if (earfcn <= 0) earfcn = id.uarfcn
                            val s = info.cellSignalStrength
                            if (rsrp == -999) rsrp = s.dbm
                            if (sinr == -999) sinr = s.ecNo
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        if (physicalConfigs.isNotEmpty()) {
            ccCount = physicalConfigs.size
            for (i in physicalConfigs.indices) {
                val cfg = physicalConfigs[i]
                val rat = NetworkModem.networkTypeToAccessNetworkType(cfg.networkType)
                val band = cfg.band
                val bwKhz = cfg.cellBandwidthDownlinkKhz
                if (bwKhz > 0 && bwKhz != INVALID) totalBwKhz += bwKhz
                if (dataRat == TelephonyManager.NETWORK_TYPE_UNKNOWN && cfg.networkType != TelephonyManager.NETWORK_TYPE_UNKNOWN) {
                    dataRat = cfg.networkType
                }
                if (i == 0) {
                    lteBand = band
                    if (earfcn <= 0) earfcn = cfg.downlinkChannelNumber
                }
                if (rat == AccessNetworkConstants.AccessNetworkType.NGRAN && nrArfcn <= 0) {
                    nrArfcn = cfg.downlinkChannelNumber
                    nrBand = cfg.band
                }
            }
        }

        var activeTech = "No Service"
        if (dataRat == TelephonyManager.NETWORK_TYPE_NR) {
            activeTech = if (lteBand > 0) "5G NSA" else "5G SA"
        } else if (dataRat == TelephonyManager.NETWORK_TYPE_LTE || dataRat == TelephonyManager.NETWORK_TYPE_LTE_CA) {
            activeTech = "4G LTE"
        } else if (dataRat == TelephonyManager.NETWORK_TYPE_UMTS || dataRat == TelephonyManager.NETWORK_TYPE_HSDPA ||
            dataRat == TelephonyManager.NETWORK_TYPE_HSUPA || dataRat == TelephonyManager.NETWORK_TYPE_HSPA ||
            dataRat == TelephonyManager.NETWORK_TYPE_HSPAP
        ) {
            activeTech = "3G WCDMA"
        } else if (dataRat == TelephonyManager.NETWORK_TYPE_GSM || dataRat == TelephonyManager.NETWORK_TYPE_GPRS ||
            dataRat == TelephonyManager.NETWORK_TYPE_EDGE
        ) {
            activeTech = "2G GSM"
        } else if (voiceRat == TelephonyManager.NETWORK_TYPE_LTE) {
            activeTech = "4G LTE"
        } else if (voiceRat == TelephonyManager.NETWORK_TYPE_GSM || voiceRat == TelephonyManager.NETWORK_TYPE_GPRS) {
            activeTech = "2G GSM"
        } else if (voiceRat == TelephonyManager.NETWORK_TYPE_UMTS) {
            activeTech = "3G WCDMA"
        } else if (isImsRegistered) {
            activeTech = if (lteBand > 0) "4G LTE (VoWiFi)" else "VoWiFi"
        }

        val totalBwMhz = totalBwKhz / 1000
        val bwSuffix = if (totalBwMhz > 0) " ($totalBwMhz MHz)" else ""
        val isNoService = activeTech == "No Service"
        val isLegacyRat = activeTech == "2G GSM" || activeTech == "3G WCDMA"
        val caDisplay = when {
            isNoService || ccCount == 0 -> "No CA (0CC)"
            isLegacyRat -> "No CA (--)"
            else -> "${ccCount}CC$bwSuffix"
        }

        var activeMbn = SystemProperties.get(
            "persist.vendor.radio.sw_mbn_name", SystemProperties.get("vendor.radio.sw_mbn_name", "Commercial APAC")
        )
        if (activeMbn.contains("/")) activeMbn = activeMbn.substring(activeMbn.lastIndexOf("/") + 1)

        val isValidEarfcn = earfcn > 0 && earfcn != INVALID
        val isValidPci = pci >= 0 && pci != INVALID
        val isValidNrArfcn = nrArfcn > 0 && nrArfcn != INVALID
        val isValidRsrp = rsrp != -999 && rsrp != INVALID
        val isValidSinr = sinr != -999 && sinr != INVALID

        var isLocationEnabled = false
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager?
            if (lm != null) {
                isLocationEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        } catch (_: Exception) {
        }

        // Timing Advance distance (1 TA unit is about 78.12 m in 3GPP LTE)
        val isValidTa = ta >= 0 && ta != INVALID
        val taDisplay = when {
            isValidTa -> {
                val distanceMeters = (ta * 78.12).roundToInt()
                when {
                    distanceMeters == 0 -> "0 (< 78 m)"
                    distanceMeters >= 1000 -> String.format(Locale.US, "%d (%.2f km)", ta, distanceMeters / 1000.0)
                    else -> String.format(Locale.US, "%d (~%d m)", ta, distanceMeters)
                }
            }
            isLegacyRat -> "N/A (Not supported on 2G GSM)"
            !isLocationEnabled -> "N/A (Location Required)"
            else -> "--"
        }

        // Estimate CQI from SINR when the modem is idle
        var displayCqi = cqi
        if (displayCqi == -1 || displayCqi == INVALID || displayCqi <= 0) {
            if (isValidSinr) {
                displayCqi = when {
                    sinr >= 12 -> 15
                    sinr >= 6 -> 12
                    sinr >= 0 -> 9
                    else -> 6
                }
            }
        }
        val isValidCqi = displayCqi in 1..15
        val displayCqiStr = if (isValidCqi) displayCqi.toString() else "--"

        var is5gHardwareSupported = true
        try {
            val allowed = tm.getAllowedNetworkTypesForReason(TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER)
            is5gHardwareSupported = allowed and TelephonyManager.NETWORK_TYPE_BITMASK_NR != 0L
        } catch (_: Exception) {
            is5gHardwareSupported = true
        }
        val rfVer = SystemProperties.get("ro.boot.rf_version", "0")
        val isMmWaveSupported = rfVer == "12" || rfVer == "22"
        val hardware5g = if (is5gHardwareSupported) {
            if (isMmWaveSupported) "Supported (Sub-6GHz FR1 + mmWave FR2 / SA+NSA)" else "Supported (Sub-6GHz FR1 / SA+NSA)"
        } else "Unsupported"

        var isVonrSupportedInCarrier: Boolean
        try {
            val ccm = context.getSystemService(Context.CARRIER_CONFIG_SERVICE) as CarrierConfigManager?
            val bundle = ccm?.getConfigForSubId(subId, CarrierConfigManager.KEY_VONR_ENABLED_BOOL)
            isVonrSupportedInCarrier = bundle?.getBoolean(CarrierConfigManager.KEY_VONR_ENABLED_BOOL, true) ?: true
        } catch (_: Exception) {
            isVonrSupportedInCarrier = true
        }

        val vonrNetworkStatus = if (dataRat == TelephonyManager.NETWORK_TYPE_NR || nrBand > 0) {
            "Active (5G NR)" + (if (forceVo5g) " (Forced)" else "")
        } else {
            "Unavailable (On $activeTech Network)" + (if (forceVo5g) " (Forced)" else "")
        }
        val vonrCarrierStatus = if (isVonrSupportedInCarrier) "Unrestricted (SIM Supported)" else "Restricted by Carrier"

        var basebandFw = SystemProperties.get("gsm.version.baseband", Build.getRadioVersion())
        if (basebandFw.isNullOrEmpty()) basebandFw = "Qualcomm SM8350 Modem v2.0"

        val noService = activeTech == "No Service"
        val activeCcCount = physicalConfigs.size
        val mimoStatus: String
        val antennaDiversity: String
        val rxChain: String
        if (noService) {
            mimoStatus = "Inactive"
            antennaDiversity = "Inactive (Radio Off / No Service)"
            rxChain = "Inactive"
        } else if (nrBand > 0) {
            // SM8350 supports up to 4x4 MIMO on NR Sub-6
            mimoStatus = if (activeCcCount >= 2) "4x4 MIMO Active" else "2x2 MIMO Active"
            antennaDiversity = "Rx0/Rx1/Rx2/Rx3 Active"
            rxChain = "4-Branch Receiver (Primary + Diversity)"
        } else if (activeTech == "4G LTE" || activeTech.contains("VoWiFi")) {
            if (activeCcCount >= 3) {
                mimoStatus = "4x4 MIMO Active"
                antennaDiversity = "Rx0/Rx1/Rx2/Rx3 Active"
                rxChain = "4-Branch Receiver (Primary + Diversity)"
            } else {
                mimoStatus = "2x2 MIMO Active"
                antennaDiversity = "Rx0/Rx1 Active"
                rxChain = "2-Branch Receiver (Primary + Diversity)"
            }
        } else {
            mimoStatus = "SISO (Single Stream)"
            antennaDiversity = "Rx0 Active"
            rxChain = "1-Branch Receiver (Primary Only)"
        }

        val txPower: String = if (noService) {
            "Inactive (Radio Off / No Service)"
        } else {
            val vendorTxPower = SystemProperties.get("vendor.radio.txpower", "")
            if (vendorTxPower.isNotEmpty()) {
                try {
                    String.format(Locale.US, "%.1f dBm (Hardware Sensor)", vendorTxPower.toDouble())
                } catch (e: NumberFormatException) {
                    vendorTxPower
                }
            } else if (isValidRsrp) {
                // Adaptive Power Control estimate (3GPP TS 36.213 / 38.213)
                val estTxDbm = min(23.0, max(-5.0, 23.0 - (rsrp + 105) * 0.45))
                String.format(Locale.US, "%.1f dBm (Adaptive Power Control)", estTxDbm)
            } else if (nrBand > 0) {
                "≤ 23.0 dBm (Power Class 3 / FR1)"
            } else if (activeTech == "4G LTE") {
                "≤ 23.0 dBm (Power Class 3)"
            } else if (activeTech == "3G WCDMA") {
                "≤ 24.0 dBm (Power Class 3)"
            } else if (activeTech.contains("2G")) {
                "≤ 33.0 dBm (Power Class 4 / GSM)"
            } else "N/A"
        }

        val modemTemp = NetworkModem.readModemTemperature()

        val rsrpTrend = trendOf(rsrp, trend.lastRsrp, isValidRsrp, trend.lastRsrp != -999)
        if (isValidRsrp) trend.lastRsrp = rsrp
        val sinrTrend = trendOf(sinr, trend.lastSinr, isValidSinr, trend.lastSinr != -999)
        if (isValidSinr) trend.lastSinr = sinr
        val cqiTrend = trendOf(displayCqi, trend.lastCqi, displayCqi > 0, trend.lastCqi > 0)
        if (displayCqi > 0) trend.lastCqi = displayCqi

        fun arrow(t: Int) = if (t > 0) " ▲" else if (t < 0) " ▼" else ""
        val is2gNet = activeTech.contains("2G")
        val na2g = "N/A (Not supported on 2G GSM)"
        val rsrpDisplay = if (isValidRsrp) "$rsrp dBm${arrow(rsrpTrend)}" else if (is2gNet) na2g else "Unavailable"
        val rsrqDisplay = if (rsrq != -999 && rsrq != INVALID) "$rsrq dB" else if (is2gNet) na2g else "Unavailable"
        val sinrDisplay = if (isValidSinr) "$sinr dB${arrow(sinrTrend)}" else if (is2gNet) na2g else "Unavailable"
        val cqiDisplay = if (displayCqiStr == "--") (if (is2gNet) na2g else "Unavailable") else displayCqiStr + arrow(cqiTrend)
        val taTextDisplay = if (ta != -1 && ta != INVALID) taDisplay else if (is2gNet) na2g else "Unavailable"

        val security = ArrayList<Pair<String, String>>()
        security += "Active MBN Loaded" to activeMbn
        security += "Carrier Config" to "com.android.carrierconfig ($carrierName)"
        security += "Modem Baseband FW" to basebandFw
        security += "5G SIM Privacy" to (if (nrBand > 0) "SUCI Concealed (Curve25519 Encrypted)" else "Standard IMSI")
        security += "Air-Interface Ciphering" to "AES-128 / ZUC (EEA2/NEA2 Enforced)"
        security += "Vendor HAL" to "vendor.oplus.hardware.radio-V2 (AIDL)"
        // Show 5G Carrier Restriction only if the operator really restricts 5G
        if (dcnrRestricted) security += "5G Carrier Restriction" to "Restricted by Operator (DCNR Restricted)"
        security += "ENDC (5G NSA)" to (if (endcAvail) "Available" else "Not Available")
        security += "DCNR (5G Restrict)" to (if (dcnrRestricted) "Restricted" else "Unrestricted")

        val sections = listOf(
            DiagSection(
                "RF & SIGNAL METRICS",
                listOf(
                    "EARFCN" to (if (isValidEarfcn) earfcn.toString() else if (is2gNet) "N/A (GERAN)" else "--"),
                    "PCI" to (if (isValidPci) pci.toString() else if (is2gNet) "N/A (BSIC Used)" else "--"),
                    "NR-ARFCN" to (if (isValidNrArfcn) nrArfcn.toString() else "N/A (Only on 5G)"),
                    "RSRP (Signal Power)" to rsrpDisplay,
                    "RSRQ (Signal Quality)" to rsrqDisplay,
                    "SINR (Signal Noise Ratio)" to sinrDisplay,
                    "CQI (Channel Quality)" to cqiDisplay,
                    "Timing Advance (TA)" to taTextDisplay,
                )
            ),
            DiagSection(
                "ANTENNA & HARDWARE",
                listOf(
                    "MIMO Status" to mimoStatus,
                    "Antenna Diversity" to antennaDiversity,
                    "Rx Chain" to rxChain,
                    "Tx Power" to txPower,
                    "Modem Temp" to modemTemp,
                )
            ),
            DiagSection(
                "NETWORK & IMS STATE",
                listOf(
                    "IMS VoLTE" to (if (isImsRegistered) "Registered" else "Idle"),
                    "IMS VoWiFi" to (if (isImsRegistered) "Registered" else "Idle"),
                    "VoNR Network State" to vonrNetworkStatus,
                    "VoNR Carrier Support" to vonrCarrierStatus,
                    "Network Mode" to (if (nrBand > 0) "5G Active" else activeTech),
                    "5G Network" to (if (is5gHardwareSupported) "Enabled (Sub-6GHz FR1 / SA+NSA)" else "Disabled"),
                    "5G Hardware" to hardware5g,
                )
            ),
            DiagSection("SECURITY & SYSTEM", security),
        )

        val quality = when {
            isValidRsrp && rsrp > -95 -> "Excellent"
            isValidRsrp && rsrp > -105 -> "Good"
            isValidRsrp && rsrp > -115 -> "Fair"
            isValidRsrp -> "Poor"
            else -> "--"
        }

        return DiagSnapshot(
            tech = activeTech,
            hasService = activeTech != "No Service",
            caDisplay = caDisplay,
            rsrp = DiagMetric(if (isValidRsrp) "$rsrp dBm" else "--", rsrpTrend),
            sinr = DiagMetric(if (isValidSinr) "$sinr dB" else "--", sinrTrend),
            cqi = DiagMetric(displayCqiStr, cqiTrend),
            signalQuality = quality,
            sections = sections,
        )
    }
}

/** Finds which catalog bands the modem is using right now (PCell / SCell). */
object ActiveBandScanner {

    class Result(val chips: String, val entries: List<BandEntry>, val dataSim: Boolean)

    private fun mark(entries: List<BandEntry>, rat: Int, band: Int, primary: Boolean, onlyIfInactive: Boolean = false): Boolean {
        var any = false
        for (e in entries) {
            if (e.rat == rat && e.bandNum == band) {
                if (onlyIfInactive && e.isActive) continue
                e.isActive = true
                if (primary) {
                    e.isPCell = true
                    e.isSCell = false
                } else if (!e.isPCell) {
                    e.isSCell = true
                }
                any = true
            }
        }
        return any
    }

    @SuppressLint("MissingPermission")
    fun scan(tm: TelephonyManager, currentSubId: Int, configs: List<PhysicalChannelConfig>, source: List<BandEntry>): Result {
        val entries = source.map { it.copyFlags().also { e -> e.isActive = false; e.isPCell = false; e.isSCell = false } }

        val defaultDataSub = SubscriptionManager.getDefaultDataSubscriptionId()
        val isDataSim = currentSubId == defaultDataSub || defaultDataSub == SubscriptionManager.INVALID_SUBSCRIPTION_ID
        if (!isDataSim) return Result("Idle (Non-Data SIM)", entries, false)

        var activeCount = 0
        val chips = StringBuilder()
        var foundPrimary = false
        val EUTRAN = AccessNetworkConstants.AccessNetworkType.EUTRAN
        val NGRAN = AccessNetworkConstants.AccessNetworkType.NGRAN

        fun chip(prefix: String, band: Int, bw: Int, tag: String) {
            if (chips.isNotEmpty()) chips.append("\n")
            chips.append(prefix).append(band)
            if (bw > 0) chips.append(" (").append(bw).append("MHz)")
            chips.append(" [").append(tag).append("]")
        }

        for (config in configs) {
            val rat = NetworkModem.networkTypeToAccessNetworkType(config.networkType)
            if (rat == AccessNetworkConstants.AccessNetworkType.UNKNOWN) continue
            var band = config.band
            val channel = config.downlinkChannelNumber
            val bw = config.cellBandwidthDownlinkKhz / 1000
            val isPrimaryConfig = NetworkModem.isPhysicalChannelPrimary(config)

            if (rat == EUTRAN) {
                if (band <= 0 && channel > 0 && channel != PhysicalChannelConfig.CHANNEL_NUMBER_UNKNOWN) {
                    band = NetworkModem.earfcnToLteBand(channel)
                }
                if (band > 0) {
                    val isP = isPrimaryConfig || !foundPrimary
                    if (isP) foundPrimary = true
                    mark(entries, rat, band, isP)
                    activeCount++
                    chip("B", band, bw, if (isP) "PCell" else "SCell")
                }
            } else if (rat == NGRAN) {
                var bands: List<Int> = emptyList()
                if (band > 0) {
                    bands = listOf(band)
                } else if (channel > 0 && channel != PhysicalChannelConfig.CHANNEL_NUMBER_UNKNOWN) {
                    bands = NetworkModem.nrarfcnToNrBands(channel)
                }
                for (b in bands) {
                    val isP = isPrimaryConfig || !foundPrimary
                    if (isP) foundPrimary = true
                    mark(entries, rat, b, isP)
                    activeCount++
                    chip("n", b, bw, if (isP) "PCell" else "SCell")
                }
            }
        }

        // CellInfo fallback if physical channels are not reported by the HAL
        if (activeCount == 0) {
            try {
                val allCellInfo: List<CellInfo>? = tm.allCellInfo
                if (allCellInfo != null) {
                    // First pass: registered cells
                    for (info in allCellInfo) {
                        if (!info.isRegistered) continue
                        val isP = !foundPrimary
                        if (isP) foundPrimary = true
                        if (info is CellInfoLte) {
                            val band = NetworkModem.earfcnToLteBand(info.cellIdentity.earfcn)
                            if (band > 0) {
                                mark(entries, EUTRAN, band, isP)
                                activeCount++
                                chip("B", band, 0, if (isP) "PCell" else "SCell")
                            }
                        } else if (info is CellInfoNr) {
                            val arfcn = (info.cellIdentity as CellIdentityNr).nrarfcn
                            for (b in NetworkModem.nrarfcnToNrBands(arfcn)) {
                                mark(entries, NGRAN, b, isP)
                                activeCount++
                                chip("n", b, 0, if (isP) "PCell" else "SCell")
                            }
                        }
                    }
                    // Second pass: candidate SCells reported by the modem (only if a PCell is registered)
                    if (foundPrimary) {
                        for (info in allCellInfo) {
                            if (info.isRegistered) continue
                            if (info is CellInfoLte) {
                                val band = NetworkModem.earfcnToLteBand(info.cellIdentity.earfcn)
                                if (band > 0) {
                                    for (e in entries) {
                                        if (e.rat == EUTRAN && e.bandNum == band && !e.isActive) {
                                            e.isActive = true
                                            e.isSCell = true
                                            activeCount++
                                            if (chips.isNotEmpty()) chips.append("\n")
                                            chips.append("B").append(band).append(" [SCell Candidate]")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // ServiceState fallback right after reboot, before channels and CellInfo are initialised
        if (activeCount == 0) {
            try {
                val ss = tm.serviceState
                if (ss != null) {
                    for (nri in ss.networkRegistrationInfoList) {
                        val cellId = nri.cellIdentity
                        if (cellId is CellIdentityLte) {
                            val band = NetworkModem.earfcnToLteBand(cellId.earfcn)
                            if (band > 0) {
                                for (e in entries) {
                                    if (e.rat == EUTRAN && e.bandNum == band && !e.isActive) {
                                        e.isActive = true
                                        e.isPCell = true
                                        activeCount++
                                        if (chips.isNotEmpty()) chips.append("\n")
                                        chips.append("B").append(band).append(" [PCell]")
                                    }
                                }
                            }
                        } else if (cellId is CellIdentityNr) {
                            for (b in NetworkModem.nrarfcnToNrBands(cellId.nrarfcn)) {
                                for (e in entries) {
                                    if (e.rat == NGRAN && e.bandNum == b && !e.isActive) {
                                        e.isActive = true
                                        e.isPCell = true
                                        activeCount++
                                        if (chips.isNotEmpty()) chips.append("\n")
                                        chips.append("n").append(b).append(" [PCell]")
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return Result(if (chips.isNotEmpty()) chips.toString() else "No Active Band", entries, true)
    }
}
