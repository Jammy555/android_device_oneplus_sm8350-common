/*
 * Copyright (C) 2025 kenrow214
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.device.DeviceSettings.powertools;

import java.util.ArrayList;
import java.util.List;

public final class KernelOptionUtils {

    public static final String CPU_LITTLE_AVAILABLE_FREQUENCIES =
            "/sys/devices/system/cpu/cpufreq/policy0/scaling_available_frequencies";
    public static final String CPU_BIG_AVAILABLE_FREQUENCIES =
            "/sys/devices/system/cpu/cpufreq/policy4/scaling_available_frequencies";
    public static final String CPU_PRIME_AVAILABLE_FREQUENCIES =
            "/sys/devices/system/cpu/cpufreq/policy7/scaling_available_frequencies";
    public static final String CPU_LITTLE_AVAILABLE_GOVERNORS =
            "/sys/devices/system/cpu/cpufreq/policy0/scaling_available_governors";
    public static final String CPU_BIG_AVAILABLE_GOVERNORS =
            "/sys/devices/system/cpu/cpufreq/policy4/scaling_available_governors";
    public static final String CPU_PRIME_AVAILABLE_GOVERNORS =
            "/sys/devices/system/cpu/cpufreq/policy7/scaling_available_governors";

    public static final String GPU_AVAILABLE_FREQUENCIES =
            "/sys/class/kgsl/kgsl-3d0/gpu_available_frequencies";
    public static final String GPU_DEVFREQ_AVAILABLE_FREQUENCIES =
            "/sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies";
    public static final String[] GPU_AVAILABLE_FREQUENCY_PATHS = {
            GPU_AVAILABLE_FREQUENCIES,
            GPU_DEVFREQ_AVAILABLE_FREQUENCIES
    };
    public static final String GPU_AVAILABLE_GOVERNORS =
            "/sys/class/kgsl/kgsl-3d0/devfreq/available_governors";

    public static final String IO_SCHEDULER = "/sys/block/sda/queue/scheduler";

    public static final String CPU_LITTLE_MIN_FREQ =
            "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq";
    public static final String CPU_LITTLE_MAX_FREQ =
            "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq";
    public static final String CPU_BIG_MIN_FREQ =
            "/sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq";
    public static final String CPU_BIG_MAX_FREQ =
            "/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq";
    public static final String CPU_PRIME_MIN_FREQ =
            "/sys/devices/system/cpu/cpufreq/policy7/scaling_min_freq";
    public static final String CPU_PRIME_MAX_FREQ =
            "/sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq";
    public static final String CPU_LITTLE_GOVERNOR =
            "/sys/devices/system/cpu/cpufreq/policy0/scaling_governor";
    public static final String CPU_BIG_GOVERNOR =
            "/sys/devices/system/cpu/cpufreq/policy4/scaling_governor";
    public static final String CPU_PRIME_GOVERNOR =
            "/sys/devices/system/cpu/cpufreq/policy7/scaling_governor";

    public static final String GPU_MIN_FREQ =
            "/sys/class/kgsl/kgsl-3d0/devfreq/min_freq";
    public static final String GPU_MAX_FREQ =
            "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq";
    public static final String GPU_GOVERNOR =
            "/sys/class/kgsl/kgsl-3d0/devfreq/governor";

    private KernelOptionUtils() {}

    public static String[] readAvailableValues(String path) {
        return readAvailableValues(path, false);
    }

    public static String[] readAvailableValues(String path, boolean dropNone) {
        String raw = SysfsUtils.readLine(path);
        if (raw == null || raw.isEmpty()) return new String[0];

        raw = raw.replace("[", "").replace("]", "");
        String[] items = raw.trim().split("\\s+");
        List<String> values = new ArrayList<>();
        for (String item : items) {
            if (item.isEmpty() || (dropNone && "none".equals(item))) continue;
            values.add(item);
        }
        return values.toArray(new String[0]);
    }

    public static String[] readAvailableValues(String[] paths, boolean dropNone) {
        if (paths == null) return new String[0];
        for (String path : paths) {
            String[] values = readAvailableValues(path, dropNone);
            if (values.length > 0) return values;
        }
        return new String[0];
    }

    public static boolean containsValue(String path, String value, boolean dropNone) {
        if (value == null || value.isEmpty()) return false;
        for (String available : readAvailableValues(path, dropNone)) {
            if (value.equals(available)) return true;
        }
        return false;
    }

    public static String findAlternateValue(String path, String current, boolean dropNone) {
        return findAlternateValue(new String[] { path }, current, dropNone);
    }

    public static String findAlternateValue(String[] paths, String current, boolean dropNone) {
        for (String available : readAvailableValues(paths, dropNone)) {
            if (!available.equals(current)) return available;
        }
        return null;
    }

    public static String readBracketedValue(String path) {
        String raw = SysfsUtils.readLine(path);
        if (raw == null || raw.isEmpty()) return null;

        int start = raw.indexOf('[');
        int end = raw.indexOf(']');
        if (start != -1 && end != -1 && start < end) {
            return raw.substring(start + 1, end);
        }
        return null;
    }

    public static String formatFrequency(String raw) {
        try {
            long frequency = Long.parseLong(raw);
            long mhz = frequency >= 10000000L ? frequency / 1000000L : frequency / 1000L;
            return mhz + " MHz";
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    public static String prettifyName(String raw) {
        if ("msm-adreno-tz".equals(raw)) return "MSM Adreno TZ";
        if ("simple_ondemand".equals(raw)) return "Simple Ondemand";
        if ("mq-deadline".equals(raw)) return "MQ-Deadline";
        if ("schedutil".equals(raw)) return "Schedutil";
        if (raw == null || raw.isEmpty()) return "";
        if (raw.length() <= 4) return raw.toUpperCase();
        return raw.substring(0, 1).toUpperCase() + raw.substring(1);
    }

    public static String displayValue(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            Long.parseLong(value);
            return formatFrequency(value);
        } catch (NumberFormatException e) {
            return prettifyName(value);
        }
    }
}
