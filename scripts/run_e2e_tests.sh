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
PORT=5554             # emulator console port; the adb serial is emulator-$PORT
OWN_PORT=false        # --port given: never touch other emulators (parallel shards)
NO_BUILD=false        # --no-build: APKs and fixtures prepared already (run_e2e_parallel.sh)
PREPARE_ONLY=false    # --prepare-only: generate fixtures and build, then exit
READ_ONLY_AVD=false   # --read-only-avd: -read-only, so several instances share the AVD
KEEP_GOING=false      # --keep-going: run every case instead of stopping at the first failure
REPORT_OVERRIDE=""

# Parse optional flags
SHOW_EMULATOR=1   # 1 = show UI (default), 0 = headless
ONLY_TESTS=""      # space-separated list of test case names to run (empty = all)
while [[ "$#" -gt 0 ]]; do
  case $1 in
    --show) SHOW_EMULATOR=1 ;;
    --headless) SHOW_EMULATOR=0 ;;
    --only) ONLY_TESTS="$ONLY_TESTS $2"; shift ;;
    --port) PORT=$2; OWN_PORT=true; shift ;;
    --slot) SLOT_FILE=$2; shift ;;
    --no-build) NO_BUILD=true ;;
    --prepare-only) PREPARE_ONLY=true ;;
    --read-only-avd) READ_ONLY_AVD=true ;;
    --keep-going) KEEP_GOING=true ;;
    --report) REPORT_OVERRIDE=$2; shift ;;
    *) echo "Unknown option: $1" ; exit 1 ;;
  esac
  shift
done

SERIAL="emulator-$PORT"

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

    for case_name in fat32 fat32_keyfile fat32_keyfile_pim exfat exfat_keyfile exfat_write fat16 serpent unsupported_cipher partitioned_mbr fat32_write; do
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

    # NTFS inside VeraCrypt and inside BitLocker, and ext4 inside VeraCrypt. Built
    # without root; a leftover expects_error.txt marks the old refusal-style cases
    # these replaced, from before ext4 (0.4.0) and NTFS (0.4.1) were supported.
    for case_name in ext4 ntfs ntfs_write bitlocker_ntfs bitlocker_ntfs_write; do
        if [ ! -f "$TESTDATA_DIR/$case_name/test.img" ] || [ -f "$TESTDATA_DIR/$case_name/expects_error.txt" ]; then
            echo "Generating no-root fixtures (ext4, NTFS in VeraCrypt and BitLocker)..."
            bash scripts/generate_noroot_testdata.sh || { echo "No-root fixture generation failed!"; exit 1; }
            break
        fi
    done

    # The support matrix: every filesystem in every container (scripts/make_e2e_matrix.py).
    if [ ! -f "$TESTDATA_DIR/m_bitlocker_ntfs/test.img" ] || [ ! -f "$TESTDATA_DIR/m_luks2_ext2/test.img" ]; then
        echo "Generating the support-matrix fixtures..."
        python3 scripts/make_e2e_matrix.py || { echo "Matrix fixture generation failed!"; exit 1; }
    fi
}

if [ "$NO_BUILD" = false ]; then
    ensure_testdata
    echo "Building Android Test APKs..."
    ./gradlew assembleDebug assembleDebugAndroidTest || { echo "Build failed!"; exit 1; }
fi
[ "$PREPARE_ONLY" = true ] && { echo "Prepared."; exit 0; }

# Kill leftover emulators from a previous run — only our own when sharded, so a
# shard never takes down its siblings.
if [ "$OWN_PORT" = true ]; then
    $CMD_ADB -s "$SERIAL" emu kill 2>/dev/null
else
    $CMD_ADB devices | grep emulator | cut -f1 | while read line; do $CMD_ADB -s "$line" emu kill 2>/dev/null; done
fi
sleep 2



# Find the first test image (alphabetically, or the one matching --only) to pre-load into the slot at boot
FIRST_IMG=""
for d in "$TESTDATA_DIR"/*/; do
    if [ -n "$ONLY_TESTS" ]; then
        _matched=false
        for _t in $ONLY_TESTS; do [ "$(basename "$d")" = "$_t" ] && _matched=true && break; done
        [ "$_matched" = false ] && continue
    fi
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
# 64 MiB: the support-matrix LUKS2 images are 56 MiB (16 MiB of LUKS2 metadata
# plus a 40 MiB filesystem, the smallest that FAT32 accepts at 512-byte clusters).
dd if=/dev/zero of="$SLOT_FILE" bs=1M count=64
echo "Slot file initialised to 64MB"

# Launch the emulator once with the slot file as a persistent USB drive backend.
# The drive backend (slot_dev) stays alive for the full run; we overwrite it inside Android.
echo "Launching Emulator with Multi-Drive support..."
QEMU_USB_FLAGS="-qemu -usb -device qemu-xhci,id=xhci \
  -blockdev driver=file,node-name=slot_file,filename=$SLOT_FILE \
  -blockdev driver=raw,node-name=slot_dev,file=slot_file \
  -device usb-storage,bus=xhci.0,drive=slot_dev,id=usbdev0,removable=on"

# -read-only gives each instance its own throwaway overlay, which is what lets
# several shards boot the same AVD; -wipe-data would write to the shared one.
DATA_FLAG="-wipe-data"
[ "$READ_ONLY_AVD" = true ] && DATA_FLAG="-read-only -no-snapshot"
WINDOW_FLAG=""
[ $SHOW_EMULATOR -eq 1 ] || WINDOW_FLAG="-no-window"
$CMD_EMULATOR -avd $AVD_NAME -port $PORT $DATA_FLAG $WINDOW_FLAG -no-audio -no-boot-anim \
    $QEMU_USB_FLAGS &
EMU_PID=$!

echo "Waiting for emulator to boot..."
$CMD_ADB -s $SERIAL wait-for-device
while [ "$($CMD_ADB -s $SERIAL shell getprop sys.boot_completed | tr -d '\r')" != "1" ]; do
    sleep 2
done
echo "Emulator booted!"

# One-time permissions setup
$CMD_ADB -s $SERIAL shell "su 0 setenforce 0"
$CMD_ADB -s $SERIAL shell "su 0 chmod a+rx /dev/block"
$CMD_ADB -s $SERIAL shell "su 0 chmod 666 /dev/block/sda"

echo "Pushing testdata to device for inside-Android swapping..."
$CMD_ADB -s $SERIAL shell "rm -rf /data/local/tmp/testdata"
if [ -n "$ONLY_TESTS" ]; then
    # Only this run's cases: the whole set is ~1.3 GB and took ~25 minutes to push.
    $CMD_ADB -s $SERIAL shell "mkdir -p /data/local/tmp/testdata"
    $CMD_ADB -s $SERIAL push "$TESTDATA_DIR/flower.jpg" /data/local/tmp/testdata/ >/dev/null
    for _t in $ONLY_TESTS; do
        [ -d "$TESTDATA_DIR/$_t" ] && $CMD_ADB -s $SERIAL push "$TESTDATA_DIR/$_t" /data/local/tmp/testdata/ >/dev/null
    done
else
    $CMD_ADB -s $SERIAL push "$TESTDATA_DIR" /data/local/tmp/
fi

# Install APKs once
echo "Installing App and Test APK..."
$CMD_ADB -s $SERIAL install -t app/build/outputs/apk/debug/app-debug.apk
$CMD_ADB -s $SERIAL install -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

echo "Starting E2E Tests..."

# Collect version/commit info for the report
APP_VERSION=$(grep 'versionName' app/build.gradle.kts 2>/dev/null | head -1 | grep -o '"[^"]*"' | tr -d '"' || echo "unknown")
GIT_COMMIT=$(git rev-parse --short HEAD 2>/dev/null || echo "unknown")
RUN_DATE=$(date '+%Y-%m-%d %H:%M:%S')
REPORT_FILE="${REPORT_OVERRIDE:-e2e_report_v${APP_VERSION}_${GIT_COMMIT}_$(date +%Y%m%d_%H%M%S).md}"

# Write report header
cat > "$REPORT_FILE" << REPORT_HEADER
# OTGMaster E2E Test Report

| Field | Value |
|-------|-------|
| Version | $APP_VERSION |
| Commit | \`$GIT_COMMIT\` |
| Date | $RUN_DATE |

## Results

| # | Test Case | Description | Result | Duration |
|---|-----------|-------------|--------|----------|
REPORT_HEADER

OVERALL_EXIT=0
IS_FIRST_TEST=true
TEST_NUM=0
PASSED_COUNT=0
FAILED_COUNT=0
FLAKY_COUNT=0

for test_dir in "$TESTDATA_DIR"/*/; do
    if [ ! -d "$test_dir" ]; then continue; fi

    TEST_NAME=$(basename "$test_dir")
    if [ -n "$ONLY_TESTS" ]; then
        _matched=false
        for _t in $ONLY_TESTS; do [ "$TEST_NAME" = "$_t" ] && _matched=true && break; done
        [ "$_matched" = false ] && continue
    fi
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

    RECOVERY_ARG=""
    if [ -f "$test_dir/recovery.txt" ]; then
        RECOVERY_ARG="-e recovery $(cat "$test_dir/recovery.txt")"
    fi
    FLOWER_ARG=""
    if [ -f "$test_dir/verify_flower.txt" ]; then
        FLOWER_ARG="-e flower_sha256 $(sha256sum "$TESTDATA_DIR/flower.jpg" | cut -d' ' -f1)"
    fi
    CONTAINER_ARG=""
    if [ -f "$test_dir/container.txt" ]; then
        CONTAINER_ARG="-e expected_container $(cat "$test_dir/container.txt")"
    fi

    WRITE_TEST_ARG=""
    if [ -f "$test_dir/write_test.txt" ]; then
        WRITE_TEST_ARG="-e write_test true"
    fi

    TEST_NUM=$((TEST_NUM + 1))
    DESCRIPTION=""
    if [ -f "$test_dir/description.txt" ]; then
        DESCRIPTION=$(cat "$test_dir/description.txt")
    fi
    TEST_START=$(date +%s)

    echo "=================================================="
    echo "Running Test Case: $TEST_NAME"
    echo "Image: $IMG_FILE"
    if [ -f "$test_dir/expects_error.txt" ]; then
        echo "Expects: error (filesystem: $EXPECTED_FS, cipher: $CIPHER)"
    else
        echo "Expects: successful mount (cipher: $CIPHER)"
    fi
    [ -n "$DESCRIPTION" ] && echo "Description: $DESCRIPTION"
    echo "=================================================="

    # QEMU hotplug is no longer used; Android E2EAutomatedTest directly overwrites /dev/block/sda using dd.

    # Clear any leftover keyfile; push the current test's keyfile if needed
    $CMD_ADB -s $SERIAL shell "rm -f /sdcard/Download/*.key"
    if [ -n "$KEYFILE" ]; then
        echo "Pushing keyfile to device..."
        $CMD_ADB -s $SERIAL push "$KEYFILE" /sdcard/Download/
        $CMD_ADB -s $SERIAL shell am broadcast \
            -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
            -d "file:///sdcard/Download/$KEYFILE_NAME"
    fi

    # Clear logcat so each test's dump is isolated
    $CMD_ADB -s $SERIAL logcat -c

    # Run test — once, and once more if it fails. A pass on the second attempt is
    # reported as FLAKY, never as a plain pass, and the first attempt's logcat and
    # screenshots are kept (logcat_<case>_attempt1.txt) so the flake can be traced.
    # UI Automator on the emulator does fail intermittently (a remount once found
    # no password field with every app log normal); one such miss used to abort a
    # two-hour run, while a real defect fails both attempts.
    ATTEMPT=1
    FLAKY=false
    while true; do
        # Overwrite the slot device with the specific test image
        echo "Writing test image: ./testdata/${TEST_NAME}/test.img to /dev/block/sda"
        $CMD_ADB -s $SERIAL shell "su 0 dd if=/data/local/tmp/testdata/${TEST_NAME}/test.img of=/dev/block/sda bs=1M conv=fsync"
        $CMD_ADB -s $SERIAL shell "su 0 sync"
        $CMD_ADB -s $SERIAL shell "su 0 sync"
        sleep 2

        echo "Running UI Automator Test..."
        TEST_OUT=$($CMD_ADB -s $SERIAL shell am instrument -w \
            -e password "$PASSWORD" \
            -e testCase "$TEST_NAME" \
            $KEYFILE_ARG \
            $PIM_ARG \
            $EXPECT_MOUNT_ARG \
            $EXPECTED_FS_ARG \
            $CIPHER_ARG \
            $REMOUNT_ARG \
            $WRITE_TEST_ARG \
            $RECOVERY_ARG \
            $CONTAINER_ARG \
            $FLOWER_ARG \
            -e class app.fayaz.otgmaster.E2EAutomatedTest \
            $PACKAGE_NAME.test/androidx.test.runner.AndroidJUnitRunner)

        echo "$TEST_OUT"

        echo "Dumping logcat for analysis:"
        $CMD_ADB -s $SERIAL logcat -d > "logcat_${TEST_NAME}.txt"
        echo "Logcat saved to logcat_${TEST_NAME}.txt"
        # Diagnostics the test saves when a wait fails (screenshot + UI hierarchy).
        for f in $($CMD_ADB -s $SERIAL shell "ls /sdcard/Download/e2e_* 2>/dev/null" | tr -d '\r'); do
            $CMD_ADB -s $SERIAL pull "$f" "e2e_${TEST_NAME}_$(basename "$f")" >/dev/null 2>&1
            $CMD_ADB -s $SERIAL shell rm -f "$f"
        done

        if echo "$TEST_OUT" | grep -q "FAILURES!!!" || echo "$TEST_OUT" | grep -q "Process crashed"; then
            TEST_EXIT_CODE=1
        else
            TEST_EXIT_CODE=0
        fi


        if [ $TEST_EXIT_CODE -ne 0 ] && [ $ATTEMPT -eq 1 ]; then
            echo "Attempt 1 of $TEST_NAME failed; retrying once."
            mv "logcat_${TEST_NAME}.txt" "logcat_${TEST_NAME}_attempt1.txt"
            for f in e2e_${TEST_NAME}_e2e_*; do [ -e "$f" ] && mv "$f" "attempt1_$f"; done
            $CMD_ADB -s $SERIAL shell am force-stop "$PACKAGE_NAME"
            ATTEMPT=2
            continue
        fi
        [ $TEST_EXIT_CODE -eq 0 ] && [ $ATTEMPT -eq 2 ] && FLAKY=true
        break
    done

    # Force-stop app to reset state; USB stays connected until the next test's usb_swap
    $CMD_ADB -s $SERIAL shell am force-stop "$PACKAGE_NAME"

    TEST_END=$(date +%s)
    DURATION=$((TEST_END - TEST_START))

    if [ $TEST_EXIT_CODE -ne 0 ]; then
        echo "TEST FAILED: $TEST_NAME"
        OVERALL_EXIT=1
        FAILED_COUNT=$((FAILED_COUNT + 1))
        echo "| $TEST_NUM | \`$TEST_NAME\` | $DESCRIPTION | ❌ FAILED | ${DURATION}s |" >> "$REPORT_FILE"
        [ "$KEEP_GOING" = true ] || break
    else
        PASSED_COUNT=$((PASSED_COUNT + 1))
        if [ "$FLAKY" = true ]; then
            echo "TEST PASSED ON RETRY (FLAKY): $TEST_NAME"
            FLAKY_COUNT=$((FLAKY_COUNT + 1))
            echo "| $TEST_NUM | \`$TEST_NAME\` | $DESCRIPTION | ⚠️ FLAKY — failed, passed on retry (logcat_${TEST_NAME}_attempt1.txt) | ${DURATION}s |" >> "$REPORT_FILE"
        else
            echo "TEST PASSED: $TEST_NAME"
            echo "| $TEST_NUM | \`$TEST_NAME\` | $DESCRIPTION | ✅ PASSED | ${DURATION}s |" >> "$REPORT_FILE"
        fi
    fi
done

echo "Killing emulator..."
$CMD_ADB -s $SERIAL emu kill
wait $EMU_PID 2>/dev/null

TOTAL_COUNT=$((PASSED_COUNT + FAILED_COUNT))
OVERALL_STATUS=$([ $OVERALL_EXIT -eq 0 ] && echo "✅ ALL PASSED" || echo "❌ FAILED")

cat >> "$REPORT_FILE" << REPORT_FOOTER

## Summary

| Metric | Value |
|--------|-------|
| Total run | $TOTAL_COUNT |
| Passed | $PASSED_COUNT |
| Failed | $FAILED_COUNT |
| Flaky (passed on retry, counted in Passed) | $FLAKY_COUNT |
| Overall | $OVERALL_STATUS |
REPORT_FOOTER

echo ""
echo "Report saved to: $REPORT_FILE"

if [ $OVERALL_EXIT -eq 0 ]; then
    echo "All tests completed successfully!"
fi
exit $OVERALL_EXIT
