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

    for case_name in fat32 fat32_keyfile fat32_keyfile_pim exfat exfat_keyfile fat16 ntfs ext4 serpent unsupported_cipher partitioned_mbr; do
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
    fi
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
dd if=/dev/zero of="$SLOT_FILE" bs=1M count=25
echo "Slot file initialised to 25MB"

# Launch the emulator once with the slot file as a persistent USB drive backend.
# The drive backend (slot_dev) stays alive for the full run; we overwrite it inside Android.
echo "Launching Emulator with Multi-Drive support..."
QEMU_USB_FLAGS="-qemu -usb -device qemu-xhci,id=xhci \
  -blockdev driver=file,node-name=slot_file,filename=$SLOT_FILE \
  -blockdev driver=raw,node-name=slot_dev,file=slot_file \
  -device usb-storage,bus=xhci.0,drive=slot_dev,id=usbdev0,removable=on"

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

echo "Pushing testdata to device for inside-Android swapping..."
$CMD_ADB -s emulator-5554 shell "rm -rf /data/local/tmp/testdata"
$CMD_ADB -s emulator-5554 push "$TESTDATA_DIR" /data/local/tmp/

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

    # QEMU hotplug is no longer used; Android E2EAutomatedTest directly overwrites /dev/block/sda using dd.

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
    # Overwrite the slot device with the specific test image
    echo "Writing test image: ./testdata/${TEST_NAME}/test.img to /dev/block/sda"
    $CMD_ADB -s emulator-5554 shell "su 0 dd if=/data/local/tmp/testdata/${TEST_NAME}/test.img of=/dev/block/sda bs=1M conv=fsync"
    $CMD_ADB -s emulator-5554 shell "su 0 sync"
    $CMD_ADB -s emulator-5554 shell "su 0 sync"
    sleep 2

    echo "Running UI Automator Test..."
    TEST_OUT=$($CMD_ADB -s emulator-5554 shell am instrument -w \
        -e password "$PASSWORD" \
        -e testCase "$TEST_NAME" \
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
