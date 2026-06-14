#!/bin/bash

# Configuration
TESTDATA_DIR="./testdata"
AVD_NAME="OTG_Test_Device"
SDK_DIR="/opt/homebrew/share/android-commandlinetools"
CMD_EMULATOR="$SDK_DIR/emulator/emulator"
CMD_ADB="adb"
PACKAGE_NAME="app.fayaz.otgmaster"

# Parse optional flags
SHOW_EMULATOR=0   # 0 = headless (default), 1 = show UI
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
    EMULATOR_OPTS="@$AVD_NAME -no-snapshot-save -gpu swiftshader_indirect"
    if [[ $SHOW_EMULATOR -eq 0 ]]; then
      EMULATOR_OPTS="$EMULATOR_OPTS -no-window"
    fi
    $CMD_EMULATOR $EMULATOR_OPTS -qemu -usb -device qemu-xhci,id=xhci \
      -blockdev driver=file,node-name=my_file,filename="$IMG_FILE" \
      -blockdev driver=raw,node-name=my_usb,file=my_file \
      -device usb-storage,bus=xhci.0,drive=my_usb,removable=true &
    
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
        -e class app.fayaz.otgmaster.E2EAutomatedTest \
        $PACKAGE_NAME.test/androidx.test.runner.AndroidJUnitRunner)
    
    echo "$TEST_OUT"

    if echo "$TEST_OUT" | grep -q "FAILURES!!!"; then
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
