/*
 * Copyright (C) 2024 The LineageOS Project
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

package org.lineageos.device.DeviceSettings.dock;

public class DockItem {

    public static final int TYPE_TEXT = 0;
    public static final int TYPE_IMAGE = 1;
    public static final int TYPE_FILE = 2;

    public long id;
    public int type;
    public String textContent;
    public String filePath;
    public String thumbnailPath;
    public String sourcePackage;
    public String sourceLabel;
    public String mimeType;
    public long timestamp;

    public DockItem() {
        this.timestamp = System.currentTimeMillis();
    }

    public DockItem(int type, String textContent, String filePath, String thumbnailPath,
                    String sourcePackage, String sourceLabel, String mimeType) {
        this.type = type;
        this.textContent = textContent;
        this.filePath = filePath;
        this.thumbnailPath = thumbnailPath;
        this.sourcePackage = sourcePackage;
        this.sourceLabel = sourceLabel;
        this.mimeType = mimeType;
        this.timestamp = System.currentTimeMillis();
    }

    public String getFormattedTime() {
        long delta = System.currentTimeMillis() - timestamp;
        if (delta < 60_000) {
            return "Just now";
        } else if (delta < 3600_000) {
            return (delta / 60_000) + "m ago";
        } else if (delta < 86400_000) {
            return (delta / 3600_000) + "h ago";
        } else {
            return (delta / 86400_000) + "d ago";
        }
    }
}
