#!/usr/bin/env python3
"""Send QEMU HMP commands to the Android emulator console on port 5554."""
import os, socket, sys, time

def main():
    cmds = sys.argv[1:]
    auth_file = os.path.expanduser("~/.emulator_console_auth_token")
    auth = open(auth_file).read().strip() if os.path.exists(auth_file) else ""

    with socket.create_connection(("127.0.0.1", 5554)) as s:
        def recv(timeout=0.5):
            s.settimeout(timeout)
            buf = b""
            try:
                while True:
                    data = s.recv(65536)
                    if not data:
                        break
                    buf += data
            except Exception:
                pass
            return buf.decode("utf-8", errors="replace").strip()

        def send(line):
            s.sendall((line + "\r\n").encode())
            time.sleep(0.4)
            return recv()

        recv()                          # eat initial greeting
        if auth:
            r = send(f"auth {auth}")
            print(f"[HMP] auth -> {r!r}", flush=True)
        r = send("qemu monitor")        # enter HMP mode
        print(f"[HMP] qemu monitor -> {r!r}", flush=True)
        time.sleep(0.3)
        recv()                          # eat HMP prompt

        for c in cmds:
            response = send(c)
            print(f"[HMP] {c!r} -> {response!r}", flush=True)

        send("quit")                    # exit HMP
        send("quit")                    # close console session

main()
