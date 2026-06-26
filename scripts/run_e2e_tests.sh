#!/bin/bash
cd "$(dirname "$0")/.." || exit 1

# Configuration
TESTDATA_DIR="./testdata"
AVD_NAME="OTG_Test_Device"
SDK_DIR="/home/fayaz/android-sdk-local"
export JAVA_HOME=/home/fayaz/android-sdk-local/jdk-17
export PATH=$JAVA_HOME/bin:$SDK_DIR/cmdline-tools/latest/bin:$SDK_DIR/platform-tools:$SDK_DIR/emulator:$PATH
export ANDROID_HOME=$SDK_DIR
export ANDROID_SDK_ROOT=$SDK_DIR
CMD_EMULATOR="emulator"
CMD_ADB="adb"
PACKAGE_NAME="app.fayaz.otgmaster"

# A temporary file that serves as the USB block device throughout the run.
# QEMU opens this once at startup and keeps the file descriptor; we overwrite
# its contents between tests so the new image is read on reconnect.
SLOT_FILE="/tmp/otg_usb_slot.img"

# Parse optional flags
SHOW_EMULATOR=1   # 1 = show UI (default), 0 = headless
ONLY_TEST=""       # if set, run just this one test case directory name
while [[ "$#" -gt 0 ]]; do
  case $1 in
    --show) SHOW_EMULATOR=1 ;;
    --headless) SHOW_EMULATOR=0 ;;
    --only) ONLY_TEST="$2"; shift ;;
    *) echo "Unknown option: $1" ; exit 1 ;;
  esac
  shift
done

if [ ! -d "$TESTDATA_DIR" ]; then
    echo "Error: $TESTDATA_DIR directory not found."
    exit 1
fi

# Verify all test case artifacts exist; regenerate if any are missing
ensure_testdata() {
    local missing=false

    if [ ! -f "$TESTDATA_DIR/flower.jpg" ]; then
        echo "Error: $TESTDATA_DIR/flower.jpg not found. Please provide it before running tests."
        exit 1
    fi

    for case_name in fat32 fat32_keyfile exfat exfat_keyfile fat16 ntfs ext4 serpent unsupported_cipher; do
        local dir="$TESTDATA_DIR/$case_name"
        if [ ! -f "$dir/test.img" ] || [ ! -f "$dir/password.txt" ] || [ ! -f "$dir/pim.txt" ]; then
            echo "Missing artifacts for test case: $case_name (test.img / password.txt / pim.txt)"
            missing=true
            break
        fi
        if [[ "$case_name" == *_keyfile ]] && [ ! -f "$dir/test.key" ]; then
            echo "Missing keyfile for test case: $case_name"
            missing=true
            break
        fi
    done

    if [ "$missing" = true ]; then
        echo "Generating test data (requires sudo for VeraCrypt mount and mkfs operations)..."
        sudo bash scripts/generate_testdata.sh || { echo "Test data generation failed!"; exit 1; }
    else
        echo "All test data artifacts present."
    fi
}

# Swap the slot file content and reconnect the USB device via QEMU HMP.
# The drive backend (slot_dev) stays alive throughout; only the USB frontend
# device is removed and re-added so Android re-enumerates with the new content.
usb_swap() {
    local img_abs="$1"
    echo "Swapping USB to: $img_abs"

    # Disconnect the USB device from Android
    python3 scripts/qemu_hmp.py "device_del usbdev0"
    sleep 1

    # Replace slot file content with the new test image (same 10 MB size)
    cp "$img_abs" "$SLOT_FILE"
    sync

    # Reconnect — QEMU re-reads from slot_dev which now maps to updated slot file
    python3 scripts/qemu_hmp.py "device_add usb-storage,id=usbdev0,drive=slot_dev,bus=xhci.0,removable=on"

    # Wait for Android to enumerate /dev/block/sda (up to 20s)
    local found=false
    for i in $(seq 1 20); do
        if $CMD_ADB -s emulator-5554 shell "ls /dev/block/sda 2>/dev/null" | grep -q sda; then
            found=true
            break
        fi
        sleep 1
    done
    if [ "$found" = false ]; then
        echo "ERROR: /dev/block/sda did not appear after USB swap"
        exit 1
    fi
    $CMD_ADB -s emulator-5554 shell "su 0 chmod 666 /dev/block/sda"
}

ensure_testdata

echo "Building Android Test APKs..."
./gradlew assembleDebug assembleDebugAndroidTest || { echo "Build failed!"; exit 1; }

# Kill any leftover emulators from a previous run
$CMD_ADB devices | grep emulator | cut -f1 | while read line; do $CMD_ADB -s "$line" emu kill 2>/dev/null; done
sleep 2

# Find the first test image (alphabetically, or the one matching --only) to pre-load into the slot at boot
FIRST_IMG=""
for d in "$TESTDATA_DIR"/*/; do
    if [ -n "$ONLY_TEST" ] && [ "$(basename "$d")" != "$ONLY_TEST" ]; then continue; fi
    img=$(find "$d" -maxdepth 1 -name "*.img" | head -n 1)
    if [ -n "$img" ]; then
        FIRST_IMG="$img"
        break
    fi
done
if [ -z "$FIRST_IMG" ]; then
    echo "Error: no test image found in $TESTDATA_DIR"
    exit 1
fi
cp "$FIRST_IMG" "$SLOT_FILE"
echo "Slot file initialised with: $FIRST_IMG"

# Launch the emulator once with the slot file as a persistent USB drive backend.
# The drive backend (slot_dev) stays alive for the full run; we only hot-remove
# and re-add the USB storage device between tests.
DUMMY_FILE="$TESTDATA_DIR/dummy.img"
echo "Launching Emulator with Multi-Drive support..."
QEMU_USB_FLAGS="-qemu -usb -device qemu-xhci,id=xhci \
  -blockdev driver=file,node-name=dummy_file,filename=$DUMMY_FILE \
  -blockdev driver=raw,node-name=dummy_dev,file=dummy_file \
  -device usb-storage,bus=xhci.0,drive=dummy_dev,id=dummy_usb,removable=true \
  -blockdev driver=file,node-name=slot_file,filename=$SLOT_FILE \
  -blockdev driver=raw,node-name=slot_dev,file=slot_file \
  -device usb-storage,bus=xhci.0,drive=slot_dev,id=usbdev0,removable=true"

if [ $SHOW_EMULATOR -eq 1 ]; then
  $CMD_EMULATOR -avd $AVD_NAME -wipe-data -no-audio -no-boot-anim \
    $QEMU_USB_FLAGS &
else
  $CMD_EMULATOR -avd $AVD_NAME -wipe-data -no-window -no-audio -no-boot-anim \
    $QEMU_USB_FLAGS &
fi
EMU_PID=$!

echo "Waiting for emulator to boot..."
$CMD_ADB -s emulator-5554 wait-for-device
while [ "$($CMD_ADB -s emulator-5554 shell getprop sys.boot_completed | tr -d '\r')" != "1" ]; do
    sleep 2
done
echo "Emulator booted!"

# One-time permissions setup
$CMD_ADB -s emulator-5554 shell "su 0 setenforce 0"
$CMD_ADB -s emulator-5554 shell "su 0 chmod a+rx /dev/block"
$CMD_ADB -s emulator-5554 shell "su 0 chmod 666 /dev/block/sda"

# Install APKs once
echo "Installing App and Test APK..."
$CMD_ADB -s emulator-5554 install -t app/build/outputs/apk/debug/app-debug.apk
$CMD_ADB -s emulator-5554 install -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

echo "Starting E2E Tests..."

OVERALL_EXIT=0
IS_FIRST_TEST=true

for test_dir in "$TESTDATA_DIR"/*/; do
    if [ ! -d "$test_dir" ]; then continue; fi

    TEST_NAME=$(basename "$test_dir")
    if [ -n "$ONLY_TEST" ] && [ "$TEST_NAME" != "$ONLY_TEST" ]; then continue; fi
    IMG_FILE=$(find "$test_dir" -maxdepth 1 -name "*.img" | head -n 1)
    PASSWORD_FILE="$test_dir/password.txt"
    KEYFILE=$(find "$test_dir" -maxdepth 1 -name "*.key" | head -n 1)

    if [ -z "$IMG_FILE" ] || [ ! -f "$PASSWORD_FILE" ]; then
        echo "Skipping $TEST_NAME: missing .img or password.txt"
        continue
    fi

    PASSWORD=$(cat "$PASSWORD_FILE")
    PIM_ARG=""
    if [ -f "$test_dir/pim.txt" ]; then
        PIM=$(cat "$test_dir/pim.txt")
        PIM_ARG="-e pim $PIM"
    fi

    KEYFILE_ARG=""
    KEYFILE_NAME=""
    if [ -n "$KEYFILE" ]; then
        KEYFILE_NAME=$(basename "$KEYFILE")
        KEYFILE_ARG="-e keyfile $KEYFILE_NAME"
    fi

    EXPECT_MOUNT_ARG="-e expect_mount true"
    EXPECTED_FS_ARG=""
    EXPECTED_FS=""
    if [ -f "$test_dir/expects_error.txt" ]; then
        EXPECT_MOUNT_ARG="-e expect_mount false"
    fi
    if [ -f "$test_dir/expected_fs.txt" ]; then
        EXPECTED_FS=$(cat "$test_dir/expected_fs.txt")
        EXPECTED_FS_ARG="-e expected_fs $EXPECTED_FS"
    fi

    CIPHER_ARG=""
    CIPHER=""
    if [ -f "$test_dir/cipher.txt" ]; then
        CIPHER=$(cat "$test_dir/cipher.txt")
        CIPHER_ARG="-e cipher $CIPHER"
    fi

    REMOUNT_ARG=""
    if [ "$TEST_NAME" = "fat32" ]; then
        REMOUNT_ARG="-e remount_test true"
    fi

    echo "=================================================="
    echo "Running Test Case: $TEST_NAME"
    echo "Image: $IMG_FILE"
    if [ -f "$test_dir/expects_error.txt" ]; then
        echo "Expects: error (filesystem: $EXPECTED_FS, cipher: $CIPHER)"
    else
        echo "Expects: successful mount (cipher: $CIPHER)"
    fi
    echo "=================================================="

    if [ "$IS_FIRST_TEST" = true ]; then
        IS_FIRST_TEST=false
        # Slot was already loaded at emulator startup; nothing to swap
        echo "Using pre-loaded slot image (first test)"
    else
        # Swap the slot file content and reconnect the USB device
        usb_swap "$(realpath "$IMG_FILE")"
    fi

    # Clear any leftover keyfile; push the current test's keyfile if needed
    $CMD_ADB -s emulator-5554 shell "rm -f /sdcard/Download/*.key"
    if [ -n "$KEYFILE" ]; then
        echo "Pushing keyfile to device..."
        $CMD_ADB -s emulator-5554 push "$KEYFILE" /sdcard/Download/
        $CMD_ADB -s emulator-5554 shell am broadcast \
            -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
            -d "file:///sdcard/Download/$KEYFILE_NAME"
    fi

    # Clear logcat so each test's dump is isolated
    $CMD_ADB -s emulator-5554 logcat -c

    # Run test
    echo "Running UI Automator Test..."
    TEST_OUT=$($CMD_ADB -s emulator-5554 shell am instrument -w \
        -e password "$PASSWORD" \
        $KEYFILE_ARG \
        $PIM_ARG \
        $EXPECT_MOUNT_ARG \
        $EXPECTED_FS_ARG \
        $CIPHER_ARG \
        $REMOUNT_ARG \
        -e class app.fayaz.otgmaster.E2EAutomatedTest \
        $PACKAGE_NAME.test/androidx.test.runner.AndroidJUnitRunner)

    echo "$TEST_OUT"

    echo "Dumping logcat for analysis:"
    $CMD_ADB -s emulator-5554 logcat -d > "logcat_${TEST_NAME}.txt"
    echo "Logcat saved to logcat_${TEST_NAME}.txt"

    if echo "$TEST_OUT" | grep -q "FAILURES!!!" || echo "$TEST_OUT" | grep -q "Process crashed"; then
        TEST_EXIT_CODE=1
    else
        TEST_EXIT_CODE=0
    fi

    # Force-stop app to reset state; USB stays connected until the next test's usb_swap
    $CMD_ADB -s emulator-5554 shell am force-stop "$PACKAGE_NAME"

    if [ $TEST_EXIT_CODE -ne 0 ]; then
        echo "TEST FAILED: $TEST_NAME"
        OVERALL_EXIT=1
        break
    else
        echo "TEST PASSED: $TEST_NAME"
    fi
done

echo "Killing emulator..."
$CMD_ADB -s emulator-5554 emu kill
wait $EMU_PID 2>/dev/null

if [ $OVERALL_EXIT -eq 0 ]; then
    echo "All tests completed successfully!"
fi
exit $OVERALL_EXIT
