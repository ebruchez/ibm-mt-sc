#ifndef MTSC_OLED_SSD1306_H
#define MTSC_OLED_SSD1306_H

#include <stdbool.h>
#include <stdint.h>

// Display dimensions for Adafruit 128x32 Monochrome OLED FeatherWing (PID 2900, SSD1306)
#define MTSC_OLED_WIDTH        128
#define MTSC_OLED_HEIGHT       32
#define MTSC_OLED_PAGES        (MTSC_OLED_HEIGHT / 8)
#define MTSC_OLED_BUFFER_BYTES (MTSC_OLED_WIDTH * MTSC_OLED_PAGES) // 512 bytes

/**
 * Probes and initializes the SSD1306 128x32 OLED FeatherWing (#2900) on I2C1
 * (`SDA = GPIO 2`, `SCL = GPIO 3`, I2C address `0x3C`).
 *
 * Safe when the OLED is NOT connected:
 * - Enables internal pull-ups on GPIO 2 / GPIO 3 first so an unconnected bus never floats LOW.
 * - Uses bounded microsecond timeouts (`i2c_write_timeout_us`) for address probing and commands.
 * - If no device ACKs `0x3C`, marks the OLED absent (`pico_oled_is_present() == false`) and returns
 *   immediately so all subsequent `pico_oled_flush` calls are zero-cost no-ops.
 */
void pico_restore_full_clock(void);
void pico_oled_init(void);

/**
 * Returns true if the SSD1306 OLED FeatherWing ACKed at I2C1 address `0x3C`.
 */
bool pico_oled_is_present(void);

/**
 * Uploads a 512-byte (`128x32` page-major) monochrome framebuffer to the SSD1306 over I2C1.
 * No-op if the OLED is not connected; automatically marks the OLED offline if an I2C timeout/NACK occurs.
 */
void pico_oled_flush(const uint8_t *frame_buf);

/**
 * Sets normal (`flip180 = false`) or 180-degree rotated (`flip180 = true`) orientation on the SSD1306.
 */
void pico_oled_set_flip(bool flip180);

/**
 * Turns the SSD1306 OLED panel and internal charge pump on (`on = true`) or puts it into
 * zero-burn-in low-power sleep (`on = false`). No-op if the OLED is absent or already in the requested state.
 */
void pico_oled_set_power(bool on);

/**
 * Returns diagnostic counters:
 * - index 0: 1 if OLED is present, 0 if absent
 * - index 1: total successful frame uploads
 * - index 2: last I2C probe/transfer result code
 */
uint32_t pico_oled_get_diag(uint32_t index);

#endif // MTSC_OLED_SSD1306_H
