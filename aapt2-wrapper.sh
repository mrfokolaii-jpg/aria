#!/bin/bash
LD_LIBRARY_PATH=/lib64 exec qemu-x86_64 /opt/android-sdk/build-tools/36.0.0/aapt2 "$@"
