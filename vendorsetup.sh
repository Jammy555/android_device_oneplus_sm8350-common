#!/bin/bash

PATCH_DIR="device/oneplus/sm8350-common/patches"

apply_patch() {
    local target_dir=$1
    local patch_file=$2
    local name=$3

    if [ -d "$target_dir" ]; then
        pushd "$target_dir" >/dev/null
        if git apply --reverse --check "$patch_file" >/dev/null 2>&1; then
            echo "${target_dir}: Already applied ($name)"
        elif git apply "$patch_file" >/dev/null 2>&1; then
            echo "${target_dir}: Successfully applied ($name)"
        else
            echo "========================================================================"
            echo " [!] MERGE CONFLICT / PATCH ERROR"
            echo " Target: ${target_dir}"
            echo " Patch Name: ${name}"
            echo " Patch File: ${patch_file}"
            echo " Details of failure:"
            git apply --check "$patch_file"
            echo "========================================================================"
        fi
        popd >/dev/null
    fi
}

apply_patch "packages/apps/GameSpace" "../../../$PATCH_DIR/gamespace_sync.patch" "GameSpace Sync"
apply_patch "frameworks/base" "../../$PATCH_DIR/frameworks_base_a0fed77.patch" "Oplus Framework Stubs"
apply_patch "device/qcom/sepolicy_vndr/legacy-um" "../../../../$PATCH_DIR/sepolicy_vndr_vendor_modprobe.patch" "Vendor Modprobe Sepolicy"
apply_patch "hardware/qcom-caf/sm8350/audio" "../../../../$PATCH_DIR/hardware_qcom_audio_lahaina_drop_hw_acc.patch" "Audio: Drop hw_acc effect"
apply_patch "frameworks/base" "../../$PATCH_DIR/0001-CachedAppOptimizer-Fix-out-of-bounds-exception-for-F.patch" "CachedAppOptimizer FULL Compaction Fix"