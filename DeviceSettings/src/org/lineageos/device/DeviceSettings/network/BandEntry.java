/*
 * Copyright (C) 2025 Lunaris Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package org.lineageos.device.DeviceSettings.network;

/**
 * Represents a single RF band entry or collapsible section header shown in the band picker UI.
 */
public final class BandEntry {
    public final int rat;
    public final int bandNum;
    public final String label;
    public final String freqHint;
    public boolean checked;
    public boolean isActive; // true = modem currently using this band
    public boolean isPCell;  // true = Primary Serving Cell
    public boolean isSCell;  // true = Secondary Serving Cell
    public boolean isHeader; // true = section header entry (e.g. 5G NR)
    public boolean isExpanded; // true = section expanded

    public BandEntry(int rat, int bandNum, String label, String freqHint) {
        this(rat, bandNum, label, freqHint, false);
    }

    public BandEntry(int rat, int bandNum, String label, String freqHint, boolean isHeader) {
        this.rat = rat;
        this.bandNum = bandNum;
        this.label = label;
        this.freqHint = freqHint;
        this.checked = false;
        this.isActive = false;
        this.isPCell = false;
        this.isSCell = false;
        this.isHeader = isHeader;
        this.isExpanded = true;
    }
}
