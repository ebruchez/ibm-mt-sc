cmake_minimum_required(VERSION 3.13)

if (NOT DEFINED PICO_SDK_PATH)
    set(PICO_SDK_PATH "/Users/ebruchez/Projects/pico/pico-sdk")
endif()

if (NOT DEFINED PICO_BOARD)
    set(PICO_BOARD "adafruit_feather_rp2040")
endif()

include(${PICO_SDK_PATH}/external/pico_sdk_import.cmake)

project(mtsc_feather_rp2040 C CXX ASM)
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

pico_sdk_init()

set(SCALA_NATIVE_LIB "${CMAKE_CURRENT_LIST_DIR}/../rp2040/target/scala-3.3.8/libmtsc_rp2040.a")

message(STATUS "Building for PICO_BOARD=${PICO_BOARD} (PICO_PLATFORM=${PICO_PLATFORM})")
message(STATUS "Linking Scala Native static library: ${SCALA_NATIVE_LIB}")

add_executable(mtsc_feather_rp2040
    main.c
    epd_ssd1680.c
    oled_ssd1306.c
)

target_compile_definitions(mtsc_feather_rp2040 PRIVATE
    PICO_STACK_SIZE=0x1000
    PICO_STDIO_USB_CONNECT_WAIT_TIMEOUT_MS=0
)

pico_generate_pio_header(mtsc_feather_rp2040 ${CMAKE_CURRENT_LIST_DIR}/ws2812.pio)

pico_enable_stdio_usb(mtsc_feather_rp2040 1)
pico_enable_stdio_uart(mtsc_feather_rp2040 0)

pico_add_extra_outputs(mtsc_feather_rp2040)

target_link_options(mtsc_feather_rp2040 PRIVATE
    -Wl,--wrap=scalanative_throw
)

target_link_libraries(mtsc_feather_rp2040
    pico_stdlib
    hardware_pio
    hardware_clocks
    hardware_spi
    hardware_i2c
    ${SCALA_NATIVE_LIB}
)
