# DeviceAsWebcam
TARGET_BUILD_DEVICE_AS_WEBCAM := true

# Torch
$(call soong_config_set,libcameraservice,ext_lib,//$(LOCAL_PATH):libcameraservice_extension.oneplus_sm8350)

# OnePlus OOS Camera
#$(call inherit-product-if-exists, vendor/oplus/camera/opluscamera.mk)

# Dolby 
#$(call inherit-product, hardware/oplus/dolby/dolby.mk)