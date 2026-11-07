#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
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

// Cortex-M0+ (ARMv6-M) faults on unaligned 32-bit loads. Scala Native's
// java.lang.String.indexOfImpl(Int, Int, Int) casts CharArray.atRaw(offset + startIndex)
// to Ptr[Long] for SWAR search, which is only 2-byte aligned when (offset + startIndex) is odd.
// Because libmtsc_rp2040.a has _SM16java.lang.StringD11indexOfImpliiiiEPT16java.lang.String
// weakened via objcopy, this strong definition overrides both cross-object and intra-object calls.
extern void scalanative_throwNullPointer(void *module)
    __asm__("_SM34scala.scalanative.runtime.package$D16throwNullPointernEO");
extern void scalanative_throwOutOfBounds(void *module, int32_t idx, int32_t len)
    __asm__("_SM34scala.scalanative.runtime.package$D16throwOutOfBoundsiinEO");

int32_t safe_String_indexOfImpl(
    void *self, int32_t codePoint, int32_t startIndex, int32_t endIndex)
    __asm__("_SM16java.lang.StringD11indexOfImpliiiiEPT16java.lang.String");

int32_t safe_String_indexOfImpl(
    void *self, int32_t codePoint, int32_t startIndex, int32_t endIndex) {
    if (self == NULL) {
        scalanative_throwNullPointer(NULL);
        return -1;
    }
    if (endIndex - startIndex < 1) {
        return -1;
    }
    const uint32_t *str_words = (const uint32_t *)self;
    uintptr_t char_array = (uintptr_t)str_words[1];
    int32_t offset = (int32_t)str_words[2];
    if (char_array == 0) {
        scalanative_throwNullPointer(NULL);
        return -1;
    }
    int32_t arr_len = *(const int32_t *)(char_array + 4u);
    const uint16_t *chars = (const uint16_t *)(char_array + 12u);

    if ((uint32_t)codePoint <= 0xFFFFu) {
        uint16_t target = (uint16_t)codePoint;
        for (int32_t i = startIndex; i < endIndex; i++) {
            int32_t idx = offset + i;
            if ((uint32_t)idx >= (uint32_t)arr_len) {
                scalanative_throwOutOfBounds(NULL, idx, arr_len);
                return -1;
            }
            if (chars[idx] == target) {
                return i;
            }
        }
        return -1;
    }

    if ((uint32_t)codePoint <= 0x10FFFFu) {
        uint32_t cp = (uint32_t)codePoint - 0x10000u;
        uint16_t hi = (uint16_t)(0xD800u + (cp >> 10));
        uint16_t lo = (uint16_t)(0xDC00u + (cp & 0x3FFu));
        for (int32_t i = startIndex; i + 1 < endIndex; i++) {
            int32_t idx = offset + i;
            if ((uint32_t)(idx + 1) >= (uint32_t)arr_len) {
                scalanative_throwOutOfBounds(NULL, idx + 1, arr_len);
                return -1;
            }
            if (chars[idx] == hi && chars[idx + 1] == lo) {
                return i;
            }
        }
    }
    return -1;
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

uint32_t pico_time_us_32(void) {
    return time_us_32();
}

// Direct zero-allocation UTF-16 java.lang.String -> UTF-8 stdout writer.
// Avoids initializing scala.Console$ (which allocates 12 KB of unused Console.in buffers),
// java.io.PrintStream, OutputStreamWriter, and UTF-8 CharsetEncoder on the RP2040 heap.
void pico_serial_print(const void *str_obj) {
    if (str_obj == NULL) {
        return;
    }
    const uint32_t *str_words = (const uint32_t *)str_obj;
    uintptr_t char_array = (uintptr_t)str_words[1];
    int32_t offset = (int32_t)str_words[2];
    int32_t count = (int32_t)str_words[3];
    if (char_array == 0 || count <= 0) {
        return;
    }
    const uint16_t *chars = (const uint16_t *)(char_array + 12u);
    char buf[256];
    size_t pos = 0;
    for (int32_t i = 0; i < count; i++) {
        uint16_t ch = chars[offset + i];
        if (pos > sizeof(buf) - 4u) {
            fwrite(buf, 1, pos, stdout);
            pos = 0;
        }
        if (ch < 0x80u) {
            buf[pos++] = (char)ch;
        } else if (ch < 0x800u) {
            buf[pos++] = (char)(0xC0u | (ch >> 6));
            buf[pos++] = (char)(0x80u | (ch & 0x3Fu));
        } else if (ch >= 0xD800u && ch <= 0xDBFFu && (i + 1) < count) {
            uint16_t lo = chars[offset + i + 1];
            if (lo >= 0xDC00u && lo <= 0xDFFFu) {
                uint32_t cp = 0x10000u + (((uint32_t)(ch - 0xD800u) << 10) | (uint32_t)(lo - 0xDC00u));
                i++;
                buf[pos++] = (char)(0xF0u | (cp >> 18));
                buf[pos++] = (char)(0x80u | ((cp >> 12) & 0x3Fu));
                buf[pos++] = (char)(0x80u | ((cp >> 6) & 0x3Fu));
                buf[pos++] = (char)(0x80u | (cp & 0x3Fu));
            } else {
                buf[pos++] = '?';
            }
        } else {
            buf[pos++] = (char)(0xE0u | (ch >> 12));
            buf[pos++] = (char)(0x80u | ((ch >> 6) & 0x3Fu));
            buf[pos++] = (char)(0x80u | (ch & 0x3Fu));
        }
    }
    if (pos > 0) {
        fwrite(buf, 1, pos, stdout);
    }
}

void pico_serial_print_chars(const void *char_array_obj, int32_t count) {
    if (char_array_obj == NULL || count <= 0) {
        return;
    }
    const uint16_t *chars = (const uint16_t *)((uintptr_t)char_array_obj + 12u);
    char buf[256];
    size_t pos = 0;
    for (int32_t i = 0; i < count; i++) {
        uint16_t ch = chars[i];
        if (pos > sizeof(buf) - 4u) {
            fwrite(buf, 1, pos, stdout);
            pos = 0;
        }
        if (ch < 0x80u) {
            buf[pos++] = (char)ch;
        } else if (ch < 0x800u) {
            buf[pos++] = (char)(0xC0u | (ch >> 6));
            buf[pos++] = (char)(0x80u | (ch & 0x3Fu));
        } else {
            buf[pos++] = (char)(0xE0u | (ch >> 12));
            buf[pos++] = (char)(0x80u | ((ch >> 6) & 0x3Fu));
            buf[pos++] = (char)(0x80u | (ch & 0x3Fu));
        }
    }
    if (pos > 0) {
        fwrite(buf, 1, pos, stdout);
    }
}

void pico_serial_flush(void) {
    fflush(stdout);
}

// Linker symbols bounding the C sbrk/malloc heap region in SRAM
extern char __end__;
extern char __HeapLimit;

volatile uint32_t boot_stage = 0;

void pico_set_boot_stage(uint32_t stage) {
    boot_stage = stage;
}

// Override for Scala Native freestanding heap size using available SRAM
size_t __scalanative_memory_size(void) {
    size_t total_heap = (size_t)(&__HeapLimit - &__end__);
    size_t c_heap_reserve = 40 * 1024; // Reserve 40 KB for Immix mark stack, Zone, stdio, and C malloc
    if (total_heap > c_heap_reserve) {
        return (total_heap - c_heap_reserve) & ~(size_t)2047;
    }
    return 64 * 1024;
}

// Override weakened Settings_MinHeapSize in libmtsc_rp2040.a so Immix unlocks all
// pre-allocated blocks immediately at startup instead of starting at 16 KB (8 blocks).
size_t Settings_MinHeapSize(void) {
    return __scalanative_memory_size();
}

// Override weak _exit in pico_runtime so _Exit(1) / abort() / OOM visibly blinks Red/Off and keeps USB alive
void __attribute__((noreturn)) _exit(int status) {
    uintptr_t caller = (uintptr_t)__builtin_return_address(0);
    uint led = pico_default_led_pin();
    while (true) {
        printf(
            "\r\n[FATAL] _exit(%d) at boot_stage=%lu caller=0x%08lx\r\n",
            status,
            (unsigned long)boot_stage,
            (unsigned long)caller
        );
        pico_neopixel_set_rgb(80, 0, 0);
        pico_led_put(led, true);
        sleep_ms(250);
        pico_neopixel_set_rgb(0, 0, 0);
        pico_led_put(led, false);
        sleep_ms(250);
    }
}

volatile uint32_t fault_frame[8];
volatile uint32_t fault_sp = 0;
volatile uint32_t fault_lr = 0;
static volatile bool hardfault_entered = false;
static uint32_t recovery_stack[512] __attribute__((aligned(8)));

static void put_str_raw(const char *s) {
    while (*s) {
        putchar_raw(*s++);
    }
}

static void put_hex32_raw(const char *label, uint32_t val) {
    static const char hex[] = "0123456789ABCDEF";
    put_str_raw(label);
    put_str_raw("0x");
    for (int shift = 28; shift >= 0; shift -= 4) {
        putchar_raw(hex[(val >> shift) & 0xFu]);
    }
}

static void put_u32_raw(const char *label, uint32_t val) {
    put_str_raw(label);
    char buf[12];
    int i = 0;
    if (val == 0) {
        putchar_raw('0');
        return;
    }
    while (val > 0 && i < 11) {
        buf[i++] = (char)('0' + (val % 10u));
        val /= 10u;
    }
    while (i > 0) {
        putchar_raw(buf[--i]);
    }
}

void __attribute__((noreturn)) hardfault_recovery_loop(void) {
    __asm volatile ("cpsie i" ::: "memory");
    uint led = pico_default_led_pin();
    uint32_t stage = boot_stage;
    if (stage == 0) {
        stage = 1;
    }
    while (true) {
        put_str_raw("\r\n[HARDFAULT]");
        put_u32_raw(" stage=", stage);
        put_hex32_raw(" PC=", fault_frame[6]);
        put_hex32_raw(" LR=", fault_frame[5]);
        put_hex32_raw(" SP=", fault_sp);
        put_hex32_raw(" EXC_LR=", fault_lr);
        put_hex32_raw(" R0=", fault_frame[0]);
        put_hex32_raw(" R1=", fault_frame[1]);
        put_hex32_raw(" R2=", fault_frame[2]);
        put_hex32_raw(" R3=", fault_frame[3]);
        put_hex32_raw(" R12=", fault_frame[4]);
        put_hex32_raw(" XPSR=", fault_frame[7]);
        put_str_raw("\r\n");
        stdio_flush();

        // Blink Orange `stage` times (1..8), then pause on Blue for 700 ms
        for (uint32_t i = 0; i < stage && i < 12; i++) {
            pico_neopixel_set_rgb(80, 24, 0);
            pico_led_put(led, true);
            sleep_ms(180);
            pico_neopixel_set_rgb(0, 0, 0);
            pico_led_put(led, false);
            sleep_ms(180);
        }
        pico_neopixel_set_rgb(0, 0, 80);
        sleep_ms(700);
    }
}

uint32_t *hardfault_c_handler(uint32_t *sp, uint32_t exc_lr) {
    if (!hardfault_entered && (exc_lr & 0xFu) != 0x1u) {
        hardfault_entered = true;
        fault_sp = (uint32_t)sp;
        fault_lr = exc_lr;
        if ((uintptr_t)sp >= 0x20000000u && (uintptr_t)sp <= 0x20041fe0u && (((uintptr_t)sp & 3u) == 0u)) {
            for (int i = 0; i < 8; i++) {
                fault_frame[i] = sp[i];
            }
        }
        // Build a clean 8-word exception return frame at the top of recovery_stack
        uint32_t *new_sp = &recovery_stack[512 - 8];
        for (int i = 0; i < 6; i++) {
            new_sp[i] = 0;
        }
        new_sp[6] = ((uint32_t)&hardfault_recovery_loop) & ~1u;
        new_sp[7] = 0x01000000u; // Thumb bit set, IPSR = 0
        return new_sp;
    }

    // Fallback if fault occurred inside an ISR (exc_lr == 0xFFFFFFF1) or recursively (blinks Orange / Green)
    uint led = pico_default_led_pin();
    uint32_t stage = boot_stage;
    if (stage == 0) {
        stage = 1;
    }
    while (true) {
        for (uint32_t i = 0; i < stage && i < 12; i++) {
            pico_neopixel_set_rgb(80, 24, 0);
            pico_led_put(led, true);
            busy_wait_us_32(180000);
            pico_neopixel_set_rgb(0, 0, 0);
            pico_led_put(led, false);
            busy_wait_us_32(180000);
        }
        pico_neopixel_set_rgb(0, 80, 0);
        busy_wait_us_32(700000);
    }
}

// Override weak Cortex-M0+ HardFault handler in crt0.S
void __attribute__((naked)) isr_hardfault(void) {
    __asm volatile (
        "mrs r0, msp\n"
        "mov r1, lr\n"
        "bl hardfault_c_handler\n"
        "msr msp, r0\n"
        "ldr r1, =0xFFFFFFF9\n"
        "bx r1\n"
    );
}

int main(void) {
    stdio_init_all();

    uint led = pico_default_led_pin();
    pico_led_init(led);
    pico_neopixel_init();
    // Stage 1 (Purple): Running ScalaNativeInit()
    boot_stage = 1;
    pico_neopixel_set_rgb(32, 0, 32);

    if (ScalaNativeInit() != 0) {
        _exit(2);
    }

    boot_stage = 2;
    mtsc_rp2040_main();
    return 0;
}
