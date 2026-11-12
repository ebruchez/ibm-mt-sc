#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include "pico/stdlib.h"
#include "pico/stdio_usb.h"
#include "hardware/gpio.h"
#include "hardware/pio.h"
#include "hardware/clocks.h"
#include "hardware/pll.h"
#include "hardware/sync.h"
#include "hardware/timer.h"
#include "hardware/xosc.h"
#include "hardware/structs/clocks.h"
#include "hardware/structs/scb.h"
#include "hardware/structs/usb.h"
#include "ws2812.pio.h"
#include "epd_ssd1680.h"
#include "oled_ssd1306.h"

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
    if (pin == 13 && pico_epd_buttons_active()) {
        return; // GPIO 13 (D13) is shared with FeatherWing Button C (active-low to GND)
    }
    gpio_init(pin);
    gpio_set_dir(pin, GPIO_OUT);
}

void pico_led_put(uint pin, bool value) {
    if (pin == 13 && pico_epd_buttons_active()) {
        return; // Avoid driving GPIO 13 HIGH against Button C pull-to-GND
    }
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
        if (grb == 0u) {
            // Wait for PIO0 to finish shifting all 24 bits (30 us @ 800 kHz) plus WS2812 latch time
            // before pico_getchar_timeout_us() gates off CLK_SYS_PIO0 in SLEEPDEEP mode.
            busy_wait_us_32(80);
        }
    }
}

static uint8_t low_clock_mode = 0; // 0 = 125 MHz (pll_sys), 1 = 48 MHz (pll_usb), 2 = 12 MHz (XOSC)

static void on_stdio_chars_available(void *param) {
    (void)param;
    __sev();
}

static bool pico_usb_bus_active(void) {
    uint32_t st = usb_hw->sie_status;
    return (st & USB_SIE_STATUS_CONNECTED_BITS) != 0u &&
           (st & USB_SIE_STATUS_SUSPENDED_BITS) == 0u;
}

// Switch clk_sys and clk_peri off the 125 MHz pll_sys and power down pll_sys (1.5 GHz VCO):
// - If a USB host bus is active (enumerated/SOF active, even without a serial terminal open),
//   clock clk_sys at 48 MHz from pll_usb so USB EP0/CDC control transfers remain instantaneous.
// - If running standalone on battery / USB power brick (no active USB host), drop clk_sys and
//   clk_peri all the way down to the 12 MHz crystal oscillator (XOSC).
static void pico_enter_low_clock_mode(void) {
    uint8_t target_mode = pico_usb_bus_active() ? 1u : 2u;
    if (low_clock_mode == target_mode) {
        return;
    }
    // Glitchlessly switch clk_sys SRC to clk_ref (12 MHz XOSC) BEFORE touching AUXSRC or pll_sys.
    // Note: Do not use clock_configure_undivided(clk_sys, 0, 0, XOSC_HZ) here because Pico SDK's
    // clock_configure_internal() writes AUXSRC=0 (pll_sys) before clearing SRC, which would stall
    // the glitchless mux if pll_sys is already powered down.
    hw_clear_bits(&clocks_hw->clk[clk_sys].ctrl, CLOCKS_CLK_SYS_CTRL_SRC_BITS);
    while (clocks_hw->clk[clk_sys].selected != 0x1u) {
        tight_loop_contents();
    }
    clock_set_reported_hz(clk_sys, XOSC_HZ);

    if (target_mode == 1u) {
        clock_configure_undivided(
            clk_sys,
            CLOCKS_CLK_SYS_CTRL_SRC_VALUE_CLKSRC_CLK_SYS_AUX,
            CLOCKS_CLK_SYS_CTRL_AUXSRC_VALUE_CLKSRC_PLL_USB,
            USB_CLK_HZ
        );
        clock_configure_undivided(
            clk_peri,
            0,
            CLOCKS_CLK_PERI_CTRL_AUXSRC_VALUE_CLKSRC_PLL_USB,
            USB_CLK_HZ
        );
    } else {
        clock_configure_undivided(
            clk_peri,
            0,
            CLOCKS_CLK_PERI_CTRL_AUXSRC_VALUE_XOSC_CLKSRC,
            XOSC_HZ
        );
    }
    if (low_clock_mode == 0u) {
        pll_deinit(pll_sys);
    }
    low_clock_mode = target_mode;
}

// Relock pll_sys to 125 MHz and glitchlessly switch clk_sys and clk_peri back to full speed.
static void pico_restore_full_clock(void) {
    if (low_clock_mode == 0u) {
        return;
    }
    // Glitchlessly switch clk_sys SRC to clk_ref (12 MHz XOSC) without altering AUXSRC
    hw_clear_bits(&clocks_hw->clk[clk_sys].ctrl, CLOCKS_CLK_SYS_CTRL_SRC_BITS);
    while (clocks_hw->clk[clk_sys].selected != 0x1u) {
        tight_loop_contents();
    }
    clock_set_reported_hz(clk_sys, XOSC_HZ);

    pll_init(pll_sys, PLL_SYS_REFDIV, PLL_SYS_VCO_FREQ_HZ, PLL_SYS_POSTDIV1, PLL_SYS_POSTDIV2);
    clock_configure_undivided(
        clk_sys,
        CLOCKS_CLK_SYS_CTRL_SRC_VALUE_CLKSRC_CLK_SYS_AUX,
        CLOCKS_CLK_SYS_CTRL_AUXSRC_VALUE_CLKSRC_PLL_SYS,
        SYS_CLK_HZ
    );
    clock_configure_undivided(
        clk_peri,
        0,
        CLOCKS_CLK_PERI_CTRL_AUXSRC_VALUE_CLK_SYS,
        SYS_CLK_HZ
    );
    low_clock_mode = 0u;
}

// Gate off RP2040 peripherals that are never used by the MT/SC firmware
// (ADC, PWM, RTC, PIO1, SPI1, I2C0, UART1) in both active and sleep modes.
static void pico_power_init_clocks(void) {
    clocks_hw->wake_en0 &= ~(
        CLOCKS_WAKE_EN0_CLK_ADC_ADC_BITS  |
        CLOCKS_WAKE_EN0_CLK_SYS_ADC_BITS  |
        CLOCKS_WAKE_EN0_CLK_SYS_PWM_BITS  |
        CLOCKS_WAKE_EN0_CLK_RTC_RTC_BITS  |
        CLOCKS_WAKE_EN0_CLK_SYS_RTC_BITS  |
        CLOCKS_WAKE_EN0_CLK_SYS_PIO1_BITS |
        CLOCKS_WAKE_EN0_CLK_PERI_SPI1_BITS |
        CLOCKS_WAKE_EN0_CLK_SYS_SPI1_BITS |
        CLOCKS_WAKE_EN0_CLK_SYS_I2C0_BITS
    );
    clocks_hw->wake_en1 &= ~(
        CLOCKS_WAKE_EN1_CLK_PERI_UART1_BITS |
        CLOCKS_WAKE_EN1_CLK_SYS_UART1_BITS
    );
    clocks_hw->sleep_en0 = clocks_hw->wake_en0;
    clocks_hw->sleep_en1 = clocks_hw->wake_en1;
    stdio_set_chars_available_callback(on_stdio_chars_available, NULL);
}

int pico_getchar_timeout_us(uint32_t timeout_us) {
    int ch = getchar_timeout_us(0);
    if (ch >= 0) {
        pico_restore_full_clock();
        return ch;
    }
    if (timeout_us == 0) {
        return PICO_ERROR_TIMEOUT;
    }
    if (pico_epd_any_button_low()) {
        pico_restore_full_clock();
        sleep_ms(5);
        return PICO_ERROR_TIMEOUT;
    }

    if (timeout_us >= 1000u && !pico_epd_is_busy()) {
        bool can_downclock = (timeout_us >= 50000u) && !stdio_usb_connected();
        uint32_t effective_wait_us = can_downclock ? 250000u : timeout_us;
        if (can_downclock) {
            pico_enter_low_clock_mode();
        } else {
            pico_restore_full_clock();
        }

        pico_epd_set_button_wake_irq(true);

        // Gate off SPI0, PIO0, I2C1, and DMA peripheral clocks whenever the Cortex-M0+ core
        // enters WFE inside best_effort_wfe_or_timeout(), while keeping SRAM0..5, XIP, ROM,
        // SIO, Timer, USB CDC, UART0, IO/Pads (for GPIO button wake IRQ), and SWD debug clocks
        // enabled so waking from WFE and SWD/USB bus access have zero AHB-Lite synchronization stall.
        uint32_t wake0 = clocks_hw->wake_en0;
        uint32_t gate0 =
            CLOCKS_SLEEP_EN0_CLK_SYS_SPI0_BITS  |
            CLOCKS_SLEEP_EN0_CLK_PERI_SPI0_BITS |
            CLOCKS_SLEEP_EN0_CLK_SYS_PIO0_BITS  |
            CLOCKS_SLEEP_EN0_CLK_SYS_I2C1_BITS  |
            CLOCKS_SLEEP_EN0_CLK_SYS_DMA_BITS;
        if (low_clock_mode != 0u) {
            gate0 |= CLOCKS_SLEEP_EN0_CLK_SYS_PLL_SYS_BITS;
        }
        clocks_hw->sleep_en0 = wake0 & ~gate0;
        scb_hw->scr |= M0PLUS_SCR_SLEEPDEEP_BITS;

        absolute_time_t until = make_timeout_time_us(effective_wait_us);
        do {
            ch = getchar_timeout_us(0);
            if (ch >= 0 || pico_epd_any_button_low()) {
                break;
            }
            if (low_clock_mode != 0u && stdio_usb_connected()) {
                break;
            }
        } while (!best_effort_wfe_or_timeout(until));

        scb_hw->scr &= ~M0PLUS_SCR_SLEEPDEEP_BITS;
        clocks_hw->sleep_en0 = wake0;
        pico_epd_set_button_wake_irq(false);

        if (ch >= 0 || pico_epd_any_button_low() || stdio_usb_connected() || !can_downclock) {
            pico_restore_full_clock();
        }
        return ch;
    }
    pico_restore_full_clock();
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

// Override for Scala Native freestanding heap size using available SRAM.
// Note: Immix `Heap_Init` divides `__scalanative_memory_size()` by 2344 bytes/block
// (2048 block + 256 bytemap + 32 lineMeta + 8 blockMeta), so metadata is already included.
size_t __scalanative_memory_size(void) {
    size_t total_heap = (size_t)(&__HeapLimit - &__end__);
    size_t c_heap_reserve = 16 * 1024; // Reserve 16 KB for Immix mark stack, Zone, and stdio
    if (total_heap > c_heap_reserve) {
        return (total_heap - c_heap_reserve) & ~(size_t)2047;
    }
    return 64 * 1024;
}

static volatile void *last_thrown_obj = NULL;
static volatile uintptr_t last_throw_caller = 0;

extern void __real_scalanative_throw(void *obj);

void __wrap_scalanative_throw(void *obj) {
    last_thrown_obj = obj;
    last_throw_caller = (uintptr_t)__builtin_return_address(0);
    __real_scalanative_throw(obj);
}

static void print_scala_string_Inline(const void *str_obj) {
    if (str_obj == NULL) {
        printf("<null>");
        return;
    }
    uintptr_t addr = (uintptr_t)str_obj;
    if (addr < 0x20000000u || addr > 0x20041f00u || (addr & 3u) != 0u) {
        printf("<invalid:0x%08lx>", (unsigned long)addr);
        return;
    }
    pico_serial_print(str_obj);
}

// Override weak _exit in pico_runtime so _Exit(1) / abort() / OOM visibly blinks Red/Off and keeps USB alive
void __attribute__((noreturn)) _exit(int status) {
    uintptr_t caller = (uintptr_t)__builtin_return_address(0);
    uint led = pico_default_led_pin();
    while (true) {
        printf(
            "\r\n[FATAL] _exit(%d) at boot_stage=%lu caller=0x%08lx throw_caller=0x%08lx",
            status,
            (unsigned long)boot_stage,
            (unsigned long)caller,
            (unsigned long)last_throw_caller
        );
        const void *obj = (const void *)last_thrown_obj;
        if (obj != NULL && (uintptr_t)obj >= 0x20000000u && (uintptr_t)obj <= 0x20041f00u) {
            const void * const *fields = (const void * const *)obj;
            const void * const *rtti = (const void * const *)fields[0];
            if ((uintptr_t)rtti >= 0x10000000u && (uintptr_t)rtti <= 0x20041f00u) {
                printf(" exc=");
                print_scala_string_Inline(rtti[4]);
            }
        }
        printf("\r\n");
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
    pico_power_init_clocks();

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
