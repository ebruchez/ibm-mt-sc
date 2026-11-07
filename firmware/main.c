#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include "pico/stdlib.h"
#include "pico/stdio_usb.h"
#include "hardware/gpio.h"
#include "hardware/pio.h"
#include "hardware/clocks.h"
#include "hardware/timer.h"
#include "ws2812.pio.h"

// Scala Native library initialization and exported Stage 1 entry point
extern int ScalaNativeInit(void);
extern void mtsc_rp2040_main(void);

// Override Scala Native freestanding time hooks using RP2040 hardware timer
int64_t scalanative_current_time_millis(void) {
    return (int64_t)(time_us_64() / 1000ULL);
}

int64_t scalanative_nano_time(void) {
    return (int64_t)(time_us_64() * 1000ULL);
}

// Thin C wrappers for Pico SDK inline GPIO functions and board macros
uint pico_default_led_pin(void) {
#ifdef PICO_DEFAULT_LED_PIN
    return PICO_DEFAULT_LED_PIN;
#else
    return 13;
#endif
}

void pico_led_init(uint pin) {
    gpio_init(pin);
    gpio_set_dir(pin, GPIO_OUT);
}

void pico_led_put(uint pin, bool value) {
    gpio_put(pin, value);
}

// Onboard WS2812 RGB NeoPixel support (GPIO 16 on Adafruit Feather RP2040)
#ifdef PICO_DEFAULT_WS2812_PIN
#define MTSC_WS2812_PIN PICO_DEFAULT_WS2812_PIN
#else
#define MTSC_WS2812_PIN 16
#endif

static bool neopixel_initialized = false;
static uint32_t last_neopixel_grb = 0xFFFFFFFFu;

void pico_neopixel_init(void) {
    if (!neopixel_initialized) {
        PIO pio = pio0;
        uint sm = 0;
        uint offset = pio_add_program(pio, &ws2812_program);
        ws2812_program_init(pio, sm, offset, MTSC_WS2812_PIN, 800000.0f, false);
        neopixel_initialized = true;
    }
}

void pico_neopixel_set_rgb(uint r, uint g, uint b) {
    if (!neopixel_initialized) {
        return;
    }
    uint32_t grb = (((uint32_t)(r & 0xFFu)) << 8) |
                   (((uint32_t)(g & 0xFFu)) << 16) |
                   ((uint32_t)(b & 0xFFu));
    if (grb != last_neopixel_grb) {
        last_neopixel_grb = grb;
        pio_sm_put_blocking(pio0, 0, grb << 8u);
    }
}

int pico_getchar_timeout_us(uint32_t timeout_us) {
    return getchar_timeout_us(timeout_us);
}

bool pico_stdio_usb_connected(void) {
    return stdio_usb_connected();
}

// Linker symbols bounding the C sbrk/malloc heap region in SRAM
extern char __end__;
extern char __HeapLimit;

// Override for Scala Native freestanding heap size using available SRAM
size_t __scalanative_memory_size(void) {
    size_t total_heap = (size_t)(&__HeapLimit - &__end__);
    size_t c_heap_reserve = 16 * 1024; // Reserve 16 KB for Zone, stdio, and C malloc
    if (total_heap > c_heap_reserve) {
        return (total_heap - c_heap_reserve) & ~(size_t)2047;
    }
    return 64 * 1024;
}

int main(void) {
    stdio_init_all();

    uint led = pico_default_led_pin();
    pico_led_init(led);
    pico_neopixel_init();

    if (ScalaNativeInit() != 0) {
        pico_neopixel_set_rgb(80, 0, 0);
        while (true) {
            puts("ScalaNativeInit failed!");
            for (int i = 0; i < 10; i++) {
                pico_led_put(led, true);
                sleep_ms(50);
                pico_led_put(led, false);
                sleep_ms(50);
            }
        }
    }

    mtsc_rp2040_main();
    return 0;
}
