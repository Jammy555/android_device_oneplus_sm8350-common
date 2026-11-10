/*
 * Copyright (C) 2026 LunarisOS
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
package org.lineageos.device.DeviceSettings.audioshare;

import android.bluetooth.BluetoothDevice;

import com.oplus.bluetooth.OplusA2dpSharingManager;

/**
 * Tiny Java shim for {@link OplusA2dpSharingManager.StateCallback}. The OPlus framework jar is not
 * visible to the Kotlin migration tooling, and an anonymous subclass written in Java compiles
 * whether StateCallback is an interface or an abstract class. Everything else is Kotlin.
 */
public final class SharingStateBridge {

    public interface Listener {
        void onStarted(BluetoothDevice primary, BluetoothDevice secondary);

        void onStopped();
    }

    private SharingStateBridge() {}

    public static OplusA2dpSharingManager.StateCallback create(final Listener listener) {
        return new OplusA2dpSharingManager.StateCallback() {
            @Override
            public void onSharingStarted(int role, int type, int reason,
                    BluetoothDevice primary, BluetoothDevice secondary) {
                listener.onStarted(primary, secondary);
            }

            @Override
            public void onSharingStopped(int role, int type, int reason,
                    BluetoothDevice primary, BluetoothDevice secondary) {
                listener.onStopped();
            }
        };
    }
}
