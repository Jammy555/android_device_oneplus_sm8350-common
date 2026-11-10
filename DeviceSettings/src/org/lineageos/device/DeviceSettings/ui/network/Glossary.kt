/*
 * Copyright (C) 2025 Lunaris Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package org.lineageos.device.DeviceSettings.ui.network

/** Help text of the advanced sheet, same wording as the old dialog. */
object Glossary {
    val sections: List<Pair<String, String>> = listOf(
        "RF & Signal Metrics Glossary" to """• Signal Quality (Excellent / Good / Fair / Poor): Overall health rating calculated directly from RSRP levels.
• RSRP (Reference Signal Received Power): Actual mathematical Signal Strength in dBm. Greater than -95 dBm is Excellent; lower than -115 dBm is Poor.
• RSRQ (Reference Signal Received Quality): Measures signal cleanliness and RF interference in dB.
• SINR / SNR (Signal-to-Interference-plus-Noise Ratio): Cleanliness of signal over background noise. Higher values mean faster speeds.
• CQI (Channel Quality Indicator): Rating scale 1–15 telling the cell tower how fast it can transmit data to your SIM.
• TA (Timing Advance): Measures approximate distance from cell tower (~0.5–1 km per step).
• EARFCN / NR-ARFCN: Radio Frequency Channel Numbers uniquely identifying 4G LTE and 5G NR carrier frequencies.
• PCI (Physical Cell ID): Hardware sector identifier (0–1007) of the active cell tower sector.""",
        "Antenna & Hardware Guide" to """• PCell (Primary Cell): Primary anchor channel handling control signals & baseline data connections.
• SCell (Secondary Cell): Extra component carriers added dynamically (2CC–4CC) to boost download speed.
• MIMO (Multiple-Input Multiple-Output): 4x4 or 2x2 parallel spatial antenna streams.
• Rx Chain / Diversity: Active receiver antenna branches (Rx0–Rx3) filtering background noise.
• Tx Power: Baseband transmit output power (Class 3 23 dBm standard).
• Modem Temp: Real-time temperature of the Qualcomm SM8350 baseband RF transceiver.""",
        "Network Security & System States" to """• Active MBN: Qualcomm modem configuration profile binary loaded for your operator.
• Carrier Config: System carrier overlay package providing operator parameters.
• IMS VoLTE / VoWiFi / VoNR: Voice over LTE, Wi-Fi, and 5G IP multimedia subsystem status.
• 5G SIM Privacy (SUCI): Elliptic curve (Curve25519) identity encryption protecting your SIM from IMSI catchers.
• Air-Interface Ciphering: Active encryption protocol (AES-128 / ZUC EEA2/NEA2) securing over-the-air data.
• ENDC: E-UTRA-NR Dual Connectivity allowing simultaneous 4G+5G connections in 5G NSA mode.
• DCNR: Dual Connectivity Needed Restriction flag set by operator.""",
        "Status & Unavailable States Meaning" to """• Spectrum Deployment Note: Spectrum allocation varies by telecom circle and local cell tower infrastructure. Even within the same network operator (e.g. Jio/Airtel/Vi/BSNL or global SIMs), not all frequency bands are broadcast in every geographical area.
• Unavailable / -- : The metric is not reported by the cell tower or modem RIL in the current state.
• N/A (Not supported on 2G GSM): 2G (GERAN) networks do not measure CQI, SINR, or Timing Advance.
• ▲ (Green Arrow): Metric value improved since last sample (higher RSRP/SINR/CQI).
• ▼ (Red Arrow): Metric value degraded since last sample.""",
    )

    val RAT_PRESETS = listOf(
        "Quick Preset: Custom Slot Combo",
        "5G + 4G Auto (Recommended)",
        "4G LTE Only (Stability)",
        "5G SA/NSA Only (High Speed)",
        "3G/2G Legacy Network",
        "2G GSM Only (Weak Signal / Power Save)",
        "Global All RATs Allowed",
    )

    const val NON_INDIA_TITLE = "Global Band Profiles Note"
    const val NON_INDIA_MESSAGE = "Welcome! Preset profiles (Recommended & Battery Saver) haven't been tailored for all regional operators. Since local spectrum allocations vary by region and the cell tower you are close to, you can customize and save your own preferred anchor & primary bands anytime using the 'Save Profile' button!"
    const val RESET_MESSAGE = "Reset all band lock filters to modem default?\n\nNote: If you still experience network issues after resetting, a device reboot will effectively restore default bands configuration."
    const val BAND_NOTE = "Note: Not all frequency bands are broadcast in every local area even if supported by your operator and country, as allocation depends on your connected cell tower infrastructure."
}
