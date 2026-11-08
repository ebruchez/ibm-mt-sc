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

// FeatherWing button bitmasks returned by pico_epd_poll_buttons()
#define MTSC_EPD_BTN_A_MASK 0x01u // Feather D11 (GPIO 11)
#define MTSC_EPD_BTN_B_MASK 0x02u // Feather D12 (GPIO 12)
#define MTSC_EPD_BTN_C_MASK 0x04u // Feather D13 (GPIO 13)

void pico_epd_init(void);
bool pico_epd_is_busy(void);
void pico_epd_upload_planes(const uint8_t *bw_plane, const uint8_t *red_plane, uint32_t mode);
uint32_t pico_epd_poll_buttons(void);
bool pico_epd_buttons_active(void);
uint32_t pico_epd_get_diag(uint32_t index);
void pico_epd_set_controller(uint32_t ctrl_type);

#endif // MTSC_EPD_SSD1680_H
