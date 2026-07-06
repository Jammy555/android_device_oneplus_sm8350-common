/*
 * Copyright (C) 2025 Lunaris Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lineageos.device.DeviceSettings.network;

/**
 * Represents a single RF band entry shown in the band picker UI.
 *
 * rat       — AccessNetworkConstants.AccessNetworkType value (GERAN/UTRAN/EUTRAN/NGRAN)
 * bandNum   — band number as defined in AccessNetworkConstants.*Band constants
 * label     — short display name, e.g. "n78" or "B40"
 * freqHint  — human-readable frequency hint, e.g. "3500 MHz (TDD)"
 * checked   — whether this band is selected by the user (will be in the lock set)
 * isActive  — true if the modem is currently camped on this band RIGHT NOW
 *              (read from PhysicalChannelConfig, updated live via TelephonyCallback)
 */
public final class BandEntry {
    public final int rat;
    public final int bandNum;
    public final String label;
    public final String freqHint;
    public boolean checked;
    public boolean isActive; // true = modem currently using this band

    public BandEntry(int rat, int bandNum, String label, String freqHint) {
        this.rat = rat;
        this.bandNum = bandNum;
        this.label = label;
        this.freqHint = freqHint;
        this.checked = false; // start unchecked — loadCurrentBands() will tick real active bands
        this.isActive = false;
    }
}
