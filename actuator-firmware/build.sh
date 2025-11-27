#!/bin/bash

set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

export CMAKE_EXPORT_COMPILE_COMMANDS=ON

if [[ ! -d 'build' ]]; then
  mkdir build
  cd build
  cmake .. -G Ninja
else
  cd build
fi

function build {
  ninja
}

openocd_args=(
  -f interface/cmsis-dap.cfg
  -f target/stm32g0x.cfg
)

function flash {
  openocd "${openocd_args[@]}" -c 'init; reset halt; program {actuator-firmware.elf} verify exit'
}

function debug {
  openocd "${openocd_args[@]}" -c 'init; reset halt'
}

case "${1:-}" in
  '' | build )
    build
  ;;

  flash | upload )
    build
    flash
  ;;

  debug )
    debug
  ;;
esac
