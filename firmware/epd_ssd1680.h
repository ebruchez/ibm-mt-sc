#ifndef MTSC_EPD_SSD1680_H
#define MTSC_EPD_SSD1680_H

#include <stdbool.h>
#include <stdint.h>

// Display dimensions in Landscape orientation (Adafruit #4777: 2.9" 296x128 SSD1680)
#define MTSC_EPD_WIDTH       296
#define MTSC_EPD_HEIGHT      128
#define MTSC_EPD_PLANE_BYTES ((MTSC_EPD_WIDTH * MTSC_EPD_HEIGHT) / 8) // 4,736 bytes

// Refresh modes for Hybrid eInk operation
#define MTSC_EPD_MODE_FAST_MONO 0 // Fast non-flashing line update (~420 ms)
#define MTSC_EPD_MODE_FULL_MONO 1 // Clean full monochrome update (~850 ms)
#define MTSC_EPD_MODE_GRAY4     2 // 4-Level Grayscale camera-ready proof update (~2,300 ms)

// 6-Button Operator Panel bitmasks returned by pico_epd_poll_buttons()
// Short-press (click < 0.7s) events: bits 0..5
#define MTSC_EPD_BTN_1_SHORT 0x00001u // Green  D12 (GPIO 12): START / STOP (Run / Pause)
#define MTSC_EPD_BTN_2_SHORT 0x00002u // Yellow D13 (GPIO 13): STEP (Single-step CCU instruction)
#define MTSC_EPD_BTN_3_SHORT 0x00004u // Red    D4  (GPIO 6):  TAPE / RESET (Rewind & Reset)
#define MTSC_EPD_BTN_4_SHORT 0x00008u // White  D11 (GPIO 11): GALLEY UP (Scroll up 4 lines)
#define MTSC_EPD_BTN_5_SHORT 0x00010u // Blue   A0  (GPIO 26): GALLEY DOWN / PROOF (Scroll down 4 lines / Proof)
#define MTSC_EPD_BTN_6_SHORT 0x00020u // Black  A1  (GPIO 27): CE MODE (Cycle OLED 11-lamp rotary mode)

// Long-press (hold >= 0.7s) events: bits 8..13
#define MTSC_EPD_BTN_1_LONG  0x00100u // Green  D12 hold: Toggle Pacing (14 cps vs Full-Speed Batch)
#define MTSC_EPD_BTN_2_LONG  0x00200u // Yellow D13 hold: Auto-Step (~10 Hz continuous stepping while held)
#define MTSC_EPD_BTN_3_LONG  0x00400u // Red    D4  hold: Next Tape Cartridge
#define MTSC_EPD_BTN_4_LONG  0x00800u // White  D11 hold: Jump to Top of Galley
#define MTSC_EPD_BTN_5_LONG  0x01000u // Blue   A0  hold: Jump to Bottom of Galley
#define MTSC_EPD_BTN_6_LONG  0x02000u // Black  A1  hold: Cycle Line Measure (140u -> 170u -> 110u)

// Immediate press-down wake flag: bit 16
#define MTSC_EPD_BTN_WAKE    0x10000u

// Legacy aliases for buttons 1..3
#define MTSC_EPD_BTN_A_MASK  MTSC_EPD_BTN_1_SHORT
#define MTSC_EPD_BTN_B_MASK  MTSC_EPD_BTN_2_SHORT
#define MTSC_EPD_BTN_C_MASK  MTSC_EPD_BTN_3_SHORT

void pico_epd_init(void);
bool pico_epd_is_busy(void);
void pico_epd_power_off(void);
void pico_epd_upload_planes(const uint8_t *bw_plane, const uint8_t *red_plane, uint32_t mode);
uint32_t pico_epd_poll_buttons(void);
uint32_t pico_epd_poll_run_interrupt(void);
bool pico_epd_buttons_active(void);
bool pico_epd_any_button_low(void);
void pico_epd_set_button_wake_irq(bool enable);
uint32_t pico_epd_get_diag(uint32_t index);
void pico_epd_set_controller(uint32_t ctrl_type);

#endif // MTSC_EPD_SSD1680_H
