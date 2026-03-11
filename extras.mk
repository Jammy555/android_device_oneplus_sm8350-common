# DeviceAsWebcam
TARGET_BUILD_DEVICE_AS_WEBCAM := true

# Torch
$(call soong_config_set,libcameraservice,ext_lib,//$(LOCAL_PATH):libcameraservice_extension.oneplus_sm8350)

# OnePlus OOS Camera
$(call inherit-product-if-exists, vendor/oplus/camera/opluscamera.mk)

# Dolby 
$(call inherit-product, vendor/oneplus/dolby/oplusdolby.mk)

# powerhal properties
PRODUCT_SYSTEM_PROPERTIES += \
    pm.sleep_mode=1 \
    ro.iorapd.enable=false \
    iorapd.perfetto.enable=false \
    persist.sys.perf.scroll_opt=true \
    persist.sys.perf.scroll_opt.heavy_app=1 \
    ro.hwui.texture_cache_size=72 \
    ro.hwui.layer_cache_size=48 \
    ro.hwui.r_buffer_cache_size=8 \
    ro.hwui.path_cache_size=32 \
    ro.hwui.gradient_cache_size=1 \
    ro.hwui.drop_shadow_cache_size=6 \
    ro.hwui.texture_cache_flushrate=0.4 \
    ro.hwui.text_small_cache_width=1024 \
    ro.hwui.text_small_cache_height=1024 \
    ro.hwui.text_large_cache_width=2048 \
    ro.hwui.text_large_cache_height=1024

PRODUCT_VENDOR_PROPERTIES += \
    vendor.post_boot.parsed=1

