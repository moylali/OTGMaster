#!/bin/bash

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

# Parse optional flags
SHOW_EMULATOR=1   # 1 = show UI (default), 0 = headless
while [[ "$#" -gt 0 ]]; do
  case $1 in
    --show) SHOW_EMULATOR=1 ;;
    --headless) SHOW_EMULATOR=0 ;;
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

    for case_name in fat32 fat32_keyfile exfat exfat_keyfile; do
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
        echo "Generating test data (requires sudo for VeraCrypt mount and mkfs.fat)..."
        sudo bash ./generate_testdata.sh || { echo "Test data generation failed!"; exit 1; }
    else
        echo "All test data artifacts present."
    fi
}

ensure_testdata

echo "Building Android Test APKs..."
./gradlew assembleDebug assembleDebugAndroidTest || { echo "Build failed!"; exit 1; }

echo "Starting E2E Tests..."

for test_dir in "$TESTDATA_DIR"/*/; do
    if [ ! -d "$test_dir" ]; then continue; fi

    TEST_NAME=$(basename "$test_dir")
    IMG_FILE=$(find "$test_dir" -maxdepth 1 -name "*.img" | head -n 1)
    PASSWORD_FILE="$test_dir/password.txt"
    KEYFILE=$(find "$test_dir" -maxdepth 1 -name "*.key" | head -n 1)

    if [ -z "$IMG_FILE" ] || [ ! -f "$PASSWORD_FILE" ]; then
        echo "Skipping $TEST_NAME: missing .img or password.txt"
        continue
    fi

    PASSWORD=$(cat "$PASSWORD_FILE")
    PIM_FILE="$test_dir/pim.txt"
    PIM_ARG=""
    if [ -f "$PIM_FILE" ]; then
        PIM=$(cat "$PIM_FILE")
        PIM_ARG="-e pim $PIM"
    fi
    
    KEYFILE_ARG=""
    if [ -n "$KEYFILE" ]; then
        KEYFILE_NAME=$(basename "$KEYFILE")
        KEYFILE_ARG="-e keyfile $KEYFILE_NAME"
    fi

    echo "=================================================="
    echo "Running Test Case: $TEST_NAME"
    echo "Image: $IMG_FILE"
    echo "Password: $PASSWORD"
    echo "=================================================="

    # Ensure no old emulators are running
    $CMD_ADB devices | grep emulator | cut -f1 | while read line; do $CMD_ADB -s $line emu kill; done
    sleep 2

    # Launch Emulator
    echo "Launching Emulator..."
    if [ $SHOW_EMULATOR -eq 1 ]; then
      $CMD_EMULATOR -avd $AVD_NAME -wipe-data -no-audio -no-boot-anim -qemu -usb -device qemu-xhci,id=xhci \
      -blockdev driver=file,node-name=my_file,filename="$IMG_FILE" \
      -blockdev driver=raw,node-name=my_usb,file=my_file \
      -device usb-storage,bus=xhci.0,drive=my_usb,removable=true &
    else
      $CMD_EMULATOR -avd $AVD_NAME -wipe-data -no-window -no-audio -no-boot-anim -qemu -usb -device qemu-xhci,id=xhci \
      -blockdev driver=file,node-name=my_file,filename="$IMG_FILE" \
      -blockdev driver=raw,node-name=my_usb,file=my_file \
      -device usb-storage,bus=xhci.0,drive=my_usb,removable=true &
    fi
    
    EMU_PID=$!

    echo "Waiting for emulator to boot..."
    $CMD_ADB -s emulator-5554 wait-for-device
    while [ "$($CMD_ADB -s emulator-5554 shell getprop sys.boot_completed | tr -d '\r')" != "1" ]; do
        sleep 2
    done
    echo "Emulator booted!"

    echo "Granting permissions to /dev/block/sda..."
    $CMD_ADB -s emulator-5554 shell "su 0 setenforce 0"
    $CMD_ADB -s emulator-5554 shell "su 0 chmod a+rx /dev/block"
    $CMD_ADB -s emulator-5554 shell "su 0 chmod 666 /dev/block/sda"

    # Install APKs
    echo "Installing App and Test APK..."
    $CMD_ADB -s emulator-5554 install -t app/build/outputs/apk/debug/app-debug.apk
    $CMD_ADB -s emulator-5554 install -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

    # Push Keyfile if it exists
    if [ -n "$KEYFILE" ]; then
        echo "Pushing Keyfile to device..."
        $CMD_ADB -s emulator-5554 push "$KEYFILE" /sdcard/Download/
        # Trigger media scanner to show it in file picker
        $CMD_ADB -s emulator-5554 shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/$KEYFILE_NAME
    fi

    # Run Test
    echo "Running UI Automator Test..."
    TEST_OUT=$($CMD_ADB -s emulator-5554 shell am instrument -w \
        -e password "$PASSWORD" \
        $KEYFILE_ARG \
        $PIM_ARG \
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

    # Cleanup
    echo "Killing emulator..."
    $CMD_ADB -s emulator-5554 emu kill
    wait $EMU_PID 2>/dev/null

    if [ $TEST_EXIT_CODE -ne 0 ]; then
        echo "TEST FAILED: $TEST_NAME"
        exit 1
    else
        echo "TEST PASSED: $TEST_NAME"
    fi
done

echo "All tests completed successfully!"
