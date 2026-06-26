#!/bin/bash
set -e

SDK_DIR="/home/fayaz/android-sdk-local"
export JAVA_HOME=/home/fayaz/android-sdk-local/jdk-17
export PATH=$JAVA_HOME/bin:$PATH
CMD_SDKMANAGER="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
CMD_AVDMANAGER="$SDK_DIR/cmdline-tools/latest/bin/avdmanager"
CMD_EMULATOR="$SDK_DIR/emulator/emulator"
AVD_NAME="OTG_Test_Device"
SYS_IMAGE="system-images;android-34;google_apis;x86_64"
IMG_FILE="$PWD/test_drive.img"

if [ ! -f "$CMD_SDKMANAGER" ]; then
    echo "Could not find sdkmanager at $CMD_SDKMANAGER. Please verify your SDK path."
    exit 1
fi

echo "Checking for system image $SYS_IMAGE..."
if [ ! -d "$SDK_DIR/system-images/android-34/google_apis/arm64-v8a" ]; then
    echo "Installing system image. This may take a while..."
    yes | $CMD_SDKMANAGER "$SYS_IMAGE"
fi

if [ ! -f "$CMD_EMULATOR" ]; then
    echo "Installing emulator..."
    yes | $CMD_SDKMANAGER "emulator"
fi

echo "Checking for AVD $AVD_NAME..."
if ! $CMD_EMULATOR -list-avds | grep -q "$AVD_NAME"; then
    echo "Creating AVD $AVD_NAME..."
    echo "no" | $CMD_AVDMANAGER create avd -n "$AVD_NAME" -k "$SYS_IMAGE" --device "pixel_6"
fi

echo "Launching emulator with VeraCrypt test drive attached..."
echo "Password for test drive: password123"

$CMD_EMULATOR @$AVD_NAME -writable-system \
  -qemu -usb \
  -device qemu-xhci,id=xhci \
  -blockdev driver=file,node-name=my_file,filename="$IMG_FILE" \
  -blockdev driver=raw,node-name=my_usb,file=my_file \
  -device usb-storage,drive=my_usb,removable=true
