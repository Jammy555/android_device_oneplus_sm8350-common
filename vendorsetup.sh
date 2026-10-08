#!/bin/bash

# Determine paths
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOP_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
PATCH_DIR="${SCRIPT_DIR}/patches"

CLEANED_REPOS=""

apply_patch() {
    local rel_target="$1"
    local patch_file="$2"
    local name="$3"

    local target_dir="${TOP_DIR}/${rel_target}"
    local patch_path="${PATCH_DIR}/${patch_file}"

    if [ ! -d "${target_dir}" ]; then
        echo "[-] ${rel_target}: directory not found, skipping (${name})"
        return 0
    fi

    if [ ! -f "${patch_path}" ]; then
        echo "[-] ${patch_file}: patch file not found, skipping (${name})"
        return 0
    fi

    pushd "${target_dir}" >/dev/null || return 1

    # Check if this repository has already been checked/cleaned in this run
    case "${CLEANED_REPOS}" in
        *" ${rel_target} "*)
            # Already checked and cleaned once during this run; do not wipe subsequent patches
            ;;
        *)
            CLEANED_REPOS="${CLEANED_REPOS} ${rel_target} "
            if [ -n "$(git status --porcelain 2>/dev/null)" ]; then
                echo "[*] ${rel_target}: Dirty changes detected. Restoring clean state..."
                git restore ./ 2>/dev/null || git checkout ./ 2>/dev/null
                git restore --staged ./ 2>/dev/null
                git clean -fd >/dev/null 2>&1
            fi
            ;;
    esac

    # Check and apply patch as dirty working-tree changes (NOT as a git commit)
    if git apply --reverse --check "${patch_path}" >/dev/null 2>&1; then
        echo "[+] ${rel_target}: Already applied / merged (${name})"
    elif git apply --check "${patch_path}" >/dev/null 2>&1; then
        git apply "${patch_path}"
        echo "[+] ${rel_target}: Successfully applied (${name})"
    else
        echo "========================================================================"
        echo " [!] ERROR: Failed to apply patch for ${rel_target}"
        echo " Patch Name: ${name}"
        echo " Patch File: ${patch_file}"
        echo " Details of failure:"
        git apply --check "${patch_path}"
        echo "========================================================================"
    fi

    popd >/dev/null
}

# Apply patches to frameworks/base
apply_patch "frameworks/base" "0001-CachedAppOptimizer-Fix-out-of-bounds-exception-for-F.patch" "CachedAppOptimizer FULL Compaction Fix"
apply_patch "frameworks/base" "frameworks_base_sandbox_abba_deadlock_fix.patch" "Sandbox AppControlController ABBA Deadlock & Non-Launcher App Fix"
apply_patch "frameworks/base" "frameworks_base_camera2_high_speed_ranges_from_supported_target.patch" "camera2: take high speed fps ranges from a supported target"
apply_patch "frameworks/base" "frameworks_base_appwidget_no_state_load_while_locked.patch" "AppWidgetService: do not load or save widget state for a credential-locked user"
apply_patch "frameworks/base" "frameworks_base_camera2_remember_missing_vendor_tags.patch" "camera2: remember a metadata key whose tag does not exist to eliminate lookup flooding"

# Apply patches to frameworks/native
apply_patch "frameworks/native" "frameworks_native_ahardwarebuffer_qti_p010_venus.patch" "libnativewindow: treat QTI YCbCr_420_P010_VENUS as YUV in lockPlanes"
apply_patch "hardware/qcom-caf/sm8350/audio" "../../../../$PATCH_DIR/hardware_qcom_caf_sm8350_audio_spkr_prot_thermalclient_soname.patch" "audio HAL spkr_prot_init(): dlopen libthermalclient.so by soname instead of the fixed /vendor/lib path, so a 64-bit libspkrprot finds the lib64 copy"