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
