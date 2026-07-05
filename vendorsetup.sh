#!/bin/bash

# Define paths
PATCH_DIR="device/oneplus/sm8350-common/patches"
GAMESPACE_DIR="packages/apps/GameSpace"
VENDOR_GMS_DIR="vendor/gms"

# Apply GameSpace live overlay sync patch if not already applied
if [ -d "$GAMESPACE_DIR" ]; then
    echo "Checking GameSpace patches..."
    cd $GAMESPACE_DIR
    
    # Check if the patch is already applied
    git diff --quiet app/src/main/java/io/chaldeaprjkt/gamespace/utils/GameModeUtils.kt
    
    # If the file hasn't been modified yet, try to patch it
    if [ $? -eq 0 ]; then
        echo "Applying GameSpace sync patch..."
        git apply ../../../$PATCH_DIR/gamespace_sync.patch >/dev/null 2>&1
        
        # Commit the patch so we know it's applied
        if [ $? -eq 0 ]; then
             git add app/src/main/java/io/chaldeaprjkt/gamespace/utils/GameModeUtils.kt
             git commit -m "gamespace-sync: Broadcast mid-game overlay changes to PowerTools"
        else
             echo "Warning: Could not apply GameSpace patch. Maybe already applied."
        fi
    else
        echo "GameSpace sync patch already active."
    fi
    
    cd ../../../
fi

# Apply vendor/gms Android.bp patch
if [ -d "$VENDOR_GMS_DIR" ]; then
    echo "Checking vendor/gms patches..."
    cd $VENDOR_GMS_DIR
    
    # Check if the patch is already applied
    git diff --quiet common/Android.bp
    
    # If the file hasn't been modified yet, try to patch it
    if [ $? -eq 0 ]; then
        echo "Applying vendor/gms Android.bp patch..."
        git apply ../../$PATCH_DIR/vendor_gms_android_bp.patch >/dev/null 2>&1
        
        # Commit the patch so we know it's applied
        if [ $? -eq 0 ]; then
             git add common/Android.bp
             git commit -m "vendor/gms: Remove Dialer and messaging overrides from Android.bp"
        else
             echo "Warning: Could not apply vendor/gms patch. Maybe already applied."
        fi
    else
        echo "vendor/gms patch already active."
    fi
    
    cd ../../
fi

# Apply frameworks/base patches
FRAMEWORKS_BASE_DIR="frameworks/base"
if [ -d "$FRAMEWORKS_BASE_DIR" ]; then
    echo "Checking frameworks/base patches..."
    cd $FRAMEWORKS_BASE_DIR
    
    # Check if the patches are already applied
    git log --oneline -n 100 | grep -q "Fixed Doze screen brightness"
    
    # If not found, apply all three as one noise
    if [ $? -ne 0 ]; then
        echo "Applying AOD and Doze brightness/voltage patches..."
        git am ../../$PATCH_DIR/9d661a537307085ebaaf35fa5f1d1de46f828240.patch >/dev/null 2>&1 || git am --abort
        git am ../../$PATCH_DIR/170c5323c8e9d8307e817739ac6eff1983b3578b.patch >/dev/null 2>&1 || git am --abort
        git am ../../$PATCH_DIR/6475b92097831d256eaf2a49cf40f515654bdf65.patch >/dev/null 2>&1 || git am --abort
    else
        echo "AOD and Doze brightness/voltage patches already active."
    fi
    
    # Check if Oplus fwb stubs patch is already applied
    git log --oneline -n 100 | grep -q "Add some fwb stubs from Oplus"
    
    if [ $? -ne 0 ]; then
        echo "Applying Oplus fwb stubs patch..."
        git am ../../$PATCH_DIR/frameworks_base_a0fed77.patch >/dev/null 2>&1 || git am --abort
    else
        echo "Oplus fwb stubs patch already active."
    fi
    
    cd ../../
fi
