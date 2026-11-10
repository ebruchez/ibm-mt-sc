#include "epd_ssd1680.h"
#include "oled_ssd1306.h"
#include "pico/stdlib.h"
#include "hardware/gpio.h"
#include "hardware/spi.h"
#include "hardware/sync.h"
#include "hardware/timer.h"

// ============================================================================
// Pin Mapping: Adafruit Feather RP2040 <-> 2.9" Grayscale eInk FeatherWing (#4777)
// ============================================================================
#define EPD_SPI_PORT spi0
#define EPD_SCK_PIN  18 // Feather SCK  (SPI0 SCK)
#define EPD_MOSI_PIN 19 // Feather MO   (SPI0 TX)
#define EPD_MISO_PIN 20 // Feather MI   (SPI0 RX)

#define EPD_SDCS_PIN 7  // Feather D5   (MicroSD CS — keep HIGH to deselect)
#define EPD_SRCS_PIN 8  // Feather D6   (SPI SRAM CS — keep HIGH to deselect)
#define EPD_ECS_PIN  9  // Feather D9   (SSD1680 E-Ink Chip Select, active LOW)
#define EPD_DC_PIN   10 // Feather D10  (SSD1680 Data/Command: LOW=Cmd, HIGH=Data)

#define EPD_BTN_A_PIN 11 // Feather D11 (Button A, active LOW)
#define EPD_BTN_B_PIN 12 // Feather D12 (Button B, active LOW)
#define EPD_BTN_C_PIN 13 // Feather D13 (Button C, active LOW — shared with Feather Red LED)

// ============================================================================
// Solomon Systech SSD1680 Register Definitions
// ============================================================================
#define SSD1680_DRIVER_CONTROL 0x01
#define SSD1680_GATE_VOLTAGE   0x03
#define SSD1680_SOURCE_VOLTAGE 0x04
#define SSD1680_DEEP_SLEEP     0x10
#define SSD1680_DATA_MODE      0x11
#define SSD1680_SW_RESET       0x12
#define SSD1680_MASTER_ACTIVATE 0x20
#define SSD1680_DISP_CTRL1     0x21
#define SSD1680_DISP_CTRL2     0x22
#define SSD1680_WRITE_RAM1     0x24
#define SSD1680_WRITE_RAM2     0x26
#define SSD1680_WRITE_VCOM     0x2C
#define SSD1680_READ_OTP       0x2D
#define SSD1680_READ_USERID    0x2E
#define SSD1680_READ_STATUS    0x2F
#define SSD1680_WRITE_LUT      0x32
#define SSD1680_WRITE_BORDER   0x3C
#define SSD1680_END_OPTION     0x3F
#define SSD1680_SET_RAMXPOS    0x44
#define SSD1680_SET_RAMYPOS    0x45
#define SSD1680_SET_RAMXCOUNT  0x4E
#define SSD1680_SET_RAMYCOUNT  0x4F
#define SSD1680_ANALOG_BLK_CTRL  0x74
#define SSD1680_DIGITAL_BLK_CTRL 0x7E

// ============================================================================
// UltraChip UC8151D / IL0373 Register Definitions (ThinkInk_290_Grayscale4_T5)
// ============================================================================
#define IL0373_PANEL_SETTING     0x00
#define IL0373_POWER_SETTING     0x01
#define IL0373_POWER_OFF         0x02
#define IL0373_POWER_ON          0x04
#define IL0373_BOOSTER_SOFT_START 0x06
#define IL0373_DTM1              0x10
#define IL0373_DISPLAY_REFRESH   0x12
#define IL0373_DTM2              0x13
#define IL0373_LUT_VCOM          0x20
#define IL0373_LUT_WW            0x21
#define IL0373_LUT_BW            0x22
#define IL0373_LUT_WB            0x23
#define IL0373_LUT_BB            0x24
#define IL0373_PLL               0x30
#define IL0373_CDI               0x50
#define IL0373_RESOLUTION        0x61
#define IL0373_GET_STATUS        0x71
#define IL0373_VCM_DC_SETTING    0x82

#define EPD_CTRL_SSD1680 1u
#define EPD_CTRL_IL0373  2u

// ============================================================================
// SWD Hardware Diagnostic Globals (inspectable via arm-none-eabi-gdb)
// ============================================================================
volatile uint32_t epd_diag_sram_status   = 0xDEAD0001u;
volatile uint32_t epd_diag_sram_readback = 0xDEAD0002u;
volatile uint32_t epd_diag_userid_hi     = 0xDEAD0003u;
volatile uint32_t epd_diag_userid_lo     = 0xDEAD0004u;
volatile uint32_t epd_diag_status_2f     = 0xDEAD0005u;
volatile uint32_t epd_diag_upload_count  = 0;
volatile uint32_t epd_diag_last_mode     = 0xFFFFFFFFu;
volatile uint32_t epd_controller_type    = EPD_CTRL_IL0373;

// ============================================================================
// Waveform Look-Up Tables (153 bytes each: 5x12 VS + 12x7 TP/SR/RP + 9 FR/XON)
// ============================================================================

// Fast non-flashing monochrome LUT (~380 ms) for live Carrier Return line updates
static const uint8_t ssd1680_fastmono_lut[153] = {
    // VS L0 (0->0 White->White), L1 (1->0 Black->White), L2 (0->1 White->Black), L3 (1->1 Black->Black), L4 (VCOM)
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // L0
    0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // L1 (Black -> White)
    0x40, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // L2 (White -> Black)
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // L3
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // L4
    // Group 0..11 timing (TP0A, TP0B, SR0AB, TP0C, TP0D, SR0CD, RP0)
    0x12, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, // Group 0: single 18-frame direct drive pulse (no inverse flash)
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // Group 1
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // Group 2
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // Group 3
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // Group 4
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // Group 5
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // Group 6
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // Group 7
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // Group 8
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // Group 9
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // Group 10
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // Group 11
    // FR, XON (9 bytes)
    0x22, 0x22, 0x22, 0x22, 0x22, 0x22, 0x00, 0x00, 0x00
};

// 4-Level Grayscale LUT for PID 4777 (GDEM029T94 / FPC-7519rev.b, DC-balanced 0x48)
// Matches adafruit_epd/ssd1680.py (_SSD1680_GRAY4_LUT) used by feather_epd_grayscale_2in9_4777.py:
//   L0 (RAM2=0, RAM1=0 -> Black)
//   L1 (RAM2=0, RAM1=1 -> Dark Gray)
//   L2 (RAM2=1, RAM1=0 -> Light Gray)
//   L3 (RAM2=1, RAM1=1 -> White)
static const uint8_t ssd1680_gray4_lut[153] = {
    0x20, 0x48, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // VS L0 (Black)
    0x08, 0x48, 0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // VS L1 (Dark Gray)
    0x02, 0x48, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // VS L2 (Light Gray)
    0x40, 0x48, 0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // VS L3 (White)
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // VS L4 (VCOM)
    0x0A, 0x19, 0x00, 0x03, 0x08, 0x00, 0x00, // Group 0
    0x14, 0x01, 0x00, 0x14, 0x01, 0x00, 0x03, // Group 1
    0x0A, 0x03, 0x00, 0x08, 0x19, 0x00, 0x00, // Group 2
    0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, // Group 3
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // Group 4
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // Group 5
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // Group 6
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // Group 7
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // Group 8
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // Group 9
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // Group 10
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // Group 11
    0x22, 0x22, 0x22, 0x22, 0x22, 0x22, 0x00, 0x00, 0x00 // FR, XON
};

// ============================================================================
// ThinkInk_290_Grayscale4_T5 (IL0373 / UC8151D) Waveform LUTs
// ============================================================================
static const uint8_t il0373_gray4_lut_vcom[42] = {
    0x00, 0x0A, 0x00, 0x00, 0x00, 0x01,
    0x60, 0x14, 0x14, 0x00, 0x00, 0x01,
    0x00, 0x14, 0x00, 0x00, 0x00, 0x01,
    0x00, 0x13, 0x0A, 0x01, 0x00, 0x01,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00
};
static const uint8_t il0373_gray4_lut_ww[42] = {
    0x40, 0x0A, 0x00, 0x00, 0x00, 0x01,
    0x90, 0x14, 0x14, 0x00, 0x00, 0x01,
    0x10, 0x14, 0x0A, 0x00, 0x00, 0x01,
    0xA0, 0x13, 0x01, 0x00, 0x00, 0x01,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00
};
// (DTM1=1, DTM2=0) -> ShadeLightGray (1): font anti-aliasing ('.') & ruler accents
// Group 3: 18f White anchor (0b01), then 2f monotonic Black drive (0b10) -> ~25% Light Gray
static const uint8_t il0373_gray4_lut_bw[42] = {
    0x40, 0x0A, 0x00, 0x00, 0x00, 0x01,
    0x90, 0x14, 0x14, 0x00, 0x00, 0x01,
    0x00, 0x14, 0x0A, 0x00, 0x00, 0x01,
    0x60, 0x12, 0x02, 0x00, 0x00, 0x01,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00
};
// (DTM1=0, DTM2=1) -> ShadeDarkGray (2): non-repro line numbers, pica ticks & margin guide
// Group 3: 14f White anchor (0b01), then 6f monotonic Black drive (0b10) -> ~60% Dark Gray
static const uint8_t il0373_gray4_lut_wb[42] = {
    0x40, 0x0A, 0x00, 0x00, 0x00, 0x01,
    0x90, 0x14, 0x14, 0x00, 0x00, 0x01,
    0x00, 0x14, 0x0A, 0x00, 0x00, 0x01,
    0x60, 0x0E, 0x06, 0x00, 0x00, 0x01,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00
};
static const uint8_t il0373_gray4_lut_bb[42] = {
    0x80, 0x0A, 0x00, 0x00, 0x00, 0x01,
    0x90, 0x14, 0x14, 0x00, 0x00, 0x01,
    0x20, 0x14, 0x0A, 0x00, 0x00, 0x01,
    0x50, 0x13, 0x01, 0x00, 0x00, 0x01,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00
};

static const uint8_t il0373_fastmono_lut_vcom[42] = {
    0x00, 0x0C, 0x00, 0x00, 0x00, 0x01,
    0,0,0,0,0,0, 0,0,0,0,0,0, 0,0,0,0,0,0, 0,0,0,0,0,0, 0,0,0,0,0,0, 0,0,0,0,0,0
};
// (DTM1=0, DTM2=0) Black -> Black: 0V hold so existing black text stays completely still
static const uint8_t il0373_fastmono_lut_ww[42] = {
    0x00, 0x0C, 0x00, 0x00, 0x00, 0x01,
    0,0,0,0,0,0, 0,0,0,0,0,0, 0,0,0,0,0,0, 0,0,0,0,0,0, 0,0,0,0,0,0, 0,0,0,0,0,0
};
// (DTM1=1, DTM2=0) White -> Black: drive Black (0x80 in L0 = level 2 = Black) for newly typed characters!
static const uint8_t il0373_fastmono_lut_bw[42] = {
    0x80, 0x0C, 0x00, 0x00, 0x00, 0x01,
    0,0,0,0,0,0, 0,0,0,0,0,0, 0,0,0,0,0,0, 0,0,0,0,0,0, 0,0,0,0,0,0, 0,0,0,0,0,0
};
// (DTM1=0, DTM2=1) Black -> White: drive White (0x40 in L0 = level 1 = White) for any erased pixels!
static const uint8_t il0373_fastmono_lut_wb[42] = {
    0x40, 0x0C, 0x00, 0x00, 0x00, 0x01,
    0,0,0,0,0,0, 0,0,0,0,0,0, 0,0,0,0,0,0, 0,0,0,0,0,0, 0,0,0,0,0,0, 0,0,0,0,0,0
};
// (DTM1=1, DTM2=1) White -> White: 0V hold so the white background never flashes
static const uint8_t il0373_fastmono_lut_bb[42] = {
    0x00, 0x0C, 0x00, 0x00, 0x00, 0x01,
    0,0,0,0,0,0, 0,0,0,0,0,0, 0,0,0,0,0,0, 0,0,0,0,0,0, 0,0,0,0,0,0, 0,0,0,0,0,0
};

static bool epd_initialized = false;
static uint64_t epd_busy_until_us = 0;
static bool il0373_fast_mono_active = false;
static bool epd_power_is_on = false;

// Shadow of previous monochrome frame for differential fast updates (stored non-inverted: 0=White, 1=Black)
static uint8_t prev_mono_ram[MTSC_EPD_PLANE_BYTES];
static bool has_prev_mono_frame = false;

// Button debounce state
static uint8_t prev_btn_mask = 0;
static uint64_t last_btn_edge_us[3] = {0, 0, 0};

// ============================================================================
// Low-Level SPI Helpers
// ============================================================================

static inline void epd_cs_low(void) {
    gpio_put(EPD_ECS_PIN, false);
}

static inline void epd_cs_high(void) {
    gpio_put(EPD_ECS_PIN, true);
}

static inline void epd_dc_cmd(void) {
    gpio_put(EPD_DC_PIN, false);
}

static inline void epd_dc_data(void) {
    gpio_put(EPD_DC_PIN, true);
}

static inline void epd_spi_transfer_byte(uint8_t b) {
    epd_cs_low();
    spi_write_blocking(EPD_SPI_PORT, &b, 1);
    epd_cs_high();
}

static void epd_write_cmd(uint8_t cmd) {
    epd_cs_high();
    epd_dc_cmd();
    epd_spi_transfer_byte(cmd);
}

static void epd_write_cmd_data(uint8_t cmd, const uint8_t *data, size_t len) {
    epd_write_cmd(cmd);
    if (data != NULL && len > 0) {
        epd_dc_data();
        for (size_t i = 0; i < len; i++) {
            epd_spi_transfer_byte(data[i]);
        }
    }
}

static void epd_write_cmd_u8(uint8_t cmd, uint8_t val) {
    epd_write_cmd_data(cmd, &val, 1);
}

// IL0373 / UC8151D uses continuous CS-low framing per command+data packet (singleByteTxns = false)
static void il0373_write_cmd(uint8_t cmd) {
    epd_cs_high();
    epd_dc_cmd();
    epd_cs_low();
    spi_write_blocking(EPD_SPI_PORT, &cmd, 1);
    epd_cs_high();
}

static void il0373_write_cmd_data(uint8_t cmd, const uint8_t *data, size_t len) {
    epd_cs_high();
    epd_dc_cmd();
    epd_cs_low();
    spi_write_blocking(EPD_SPI_PORT, &cmd, 1);
    if (data != NULL && len > 0) {
        epd_dc_data();
        spi_write_blocking(EPD_SPI_PORT, data, len);
    }
    epd_cs_high();
}

static void il0373_write_cmd_u8(uint8_t cmd, uint8_t val) {
    il0373_write_cmd_data(cmd, &val, 1);
}

// Half-duplex bitbang read of E-Ink register on MOSI (GPIO 19) with selectable pull-up or pull-down
static void epd_bitbang_read_reg_pull(uint8_t cmd, uint8_t *out_buf, size_t len, bool use_pull_up) {
    gpio_init(EPD_SCK_PIN);
    gpio_set_dir(EPD_SCK_PIN, GPIO_OUT);
    gpio_put(EPD_SCK_PIN, false);

    gpio_init(EPD_MOSI_PIN);
    gpio_set_dir(EPD_MOSI_PIN, GPIO_OUT);
    gpio_put(EPD_MOSI_PIN, false);

    epd_cs_high();
    sleep_us(5);
    epd_dc_cmd();
    epd_cs_low();
    sleep_us(5);

    for (int b = 7; b >= 0; b--) {
        gpio_put(EPD_MOSI_PIN, (cmd >> b) & 1);
        sleep_us(2);
        gpio_put(EPD_SCK_PIN, true);
        sleep_us(2);
        gpio_put(EPD_SCK_PIN, false);
    }

    gpio_set_dir(EPD_MOSI_PIN, GPIO_IN);
    if (use_pull_up) {
        gpio_pull_up(EPD_MOSI_PIN);
    } else {
        gpio_pull_down(EPD_MOSI_PIN);
    }
    epd_dc_data();
    sleep_us(5);

    for (size_t i = 0; i < len; i++) {
        uint8_t val = 0;
        for (int b = 7; b >= 0; b--) {
            sleep_us(2);
            gpio_put(EPD_SCK_PIN, true);
            sleep_us(2);
            if (gpio_get(EPD_MOSI_PIN)) {
                val |= (uint8_t)(1u << b);
            }
            gpio_put(EPD_SCK_PIN, false);
        }
        out_buf[i] = val;
    }

    sleep_us(5);
    epd_cs_high();
}

// Diagnostic test of FeatherWing's onboard Microchip 23K640 SPI SRAM (SRCS = GPIO 8)
static void epd_diag_probe_sram(void) {
    // Write Status Register (0x01) = 0x41 (Sequential mode)
    const uint8_t wrsr[2] = {0x01, 0x41};
    gpio_put(EPD_SRCS_PIN, false);
    spi_write_blocking(EPD_SPI_PORT, wrsr, 2);
    gpio_put(EPD_SRCS_PIN, true);
    sleep_us(5);

    // Read Status Register (0x05)
    const uint8_t rdsr_tx[2] = {0x05, 0x00};
    uint8_t rdsr_rx[2] = {0, 0};
    gpio_put(EPD_SRCS_PIN, false);
    spi_write_read_blocking(EPD_SPI_PORT, rdsr_tx, rdsr_rx, 2);
    gpio_put(EPD_SRCS_PIN, true);
    epd_diag_sram_status = rdsr_rx[1];
    sleep_us(5);

    // Write 4 test bytes at SRAM address 0x0000
    const uint8_t wr_cmd[7] = {0x02, 0x00, 0x00, 0xA5, 0x5A, 0x12, 0x34};
    gpio_put(EPD_SRCS_PIN, false);
    spi_write_blocking(EPD_SPI_PORT, wr_cmd, 7);
    gpio_put(EPD_SRCS_PIN, true);
    sleep_us(5);

    // Read 4 test bytes back from SRAM address 0x0000
    const uint8_t rd_tx[7] = {0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
    uint8_t rd_rx[7] = {0};
    gpio_put(EPD_SRCS_PIN, false);
    spi_write_read_blocking(EPD_SPI_PORT, rd_tx, rd_rx, 7);
    gpio_put(EPD_SRCS_PIN, true);
    epd_diag_sram_readback =
        ((uint32_t)rd_rx[3] << 24) |
        ((uint32_t)rd_rx[4] << 16) |
        ((uint32_t)rd_rx[5] << 8)  |
        ((uint32_t)rd_rx[6]);
}

static void epd_set_ram_window_and_cursor(void) {
    // RAM X start = 0x00, end = 0x0F (16 bytes * 8 bits = 128 pixels)
    const uint8_t x_win[2] = {0x00, 0x0F};
    epd_write_cmd_data(SSD1680_SET_RAMXPOS, x_win, 2);

    // RAM Y start = 0x0000, end = 0x0127 (295 -> 296 gates)
    const uint8_t y_win[4] = {0x00, 0x00, 0x27, 0x01};
    epd_write_cmd_data(SSD1680_SET_RAMYPOS, y_win, 4);

    // Reset X/Y counters to (0, 0)
    epd_write_cmd_u8(SSD1680_SET_RAMXCOUNT, 0x00);
    const uint8_t y_cnt[2] = {0x00, 0x00};
    epd_write_cmd_data(SSD1680_SET_RAMYCOUNT, y_cnt, 2);
}

static void ssd1680_power_up(uint32_t mode) {
    epd_write_cmd(SSD1680_SW_RESET);
    sleep_ms(100);

    // Driver output control: 296 gates (0x0127)
    const uint8_t drv_ctrl[3] = {0x27, 0x01, 0x00};
    epd_write_cmd_data(SSD1680_DRIVER_CONTROL, drv_ctrl, 3);

    // Data entry mode: X increment, Y increment
    epd_write_cmd_u8(SSD1680_DATA_MODE, 0x03);

    epd_set_ram_window_and_cursor();

    if (mode == MTSC_EPD_MODE_GRAY4) {
        // 4-Grayscale mode matching ThinkInk_290_Grayscale4_EAAMFGN / FPC7519:
        // VSH1 = 0x41 (15V), VSH2 = 0xAE (5V), VSL = 0x32 (-15V), VCOM = 0x24
        epd_write_cmd_u8(SSD1680_WRITE_BORDER, 0x04);
        epd_write_cmd_u8(SSD1680_WRITE_VCOM, 0x24);
        epd_write_cmd_u8(SSD1680_END_OPTION, 0x22);
        epd_write_cmd_u8(SSD1680_GATE_VOLTAGE, 0x17);
        const uint8_t src_v[3] = {0x41, 0xAE, 0x32};
        epd_write_cmd_data(SSD1680_SOURCE_VOLTAGE, src_v, 3);
        epd_write_cmd_data(SSD1680_WRITE_LUT, ssd1680_gray4_lut, sizeof(ssd1680_gray4_lut));
        epd_set_ram_window_and_cursor();
    } else if (mode == MTSC_EPD_MODE_FAST_MONO) {
        // Fast differential monochrome update: RAM1 (0x24) = new BW, RAM2 (0x26) = old BW
        epd_write_cmd_u8(SSD1680_WRITE_BORDER, 0x05);
        epd_write_cmd_u8(SSD1680_WRITE_VCOM, 0x24);
        epd_write_cmd_u8(SSD1680_END_OPTION, 0x22);
        epd_write_cmd_u8(SSD1680_GATE_VOLTAGE, 0x17);
        const uint8_t src_v[3] = {0x41, 0xAE, 0x32};
        epd_write_cmd_data(SSD1680_SOURCE_VOLTAGE, src_v, 3);
        epd_write_cmd_data(SSD1680_WRITE_LUT, ssd1680_fastmono_lut, sizeof(ssd1680_fastmono_lut));
        epd_set_ram_window_and_cursor();
    } else {
        // Full monochrome update using factory OTP waveform (DISP_CTRL2 = 0xF4, matching Adafruit_SSD1680)
        epd_write_cmd_u8(SSD1680_WRITE_VCOM, 0x36);
        epd_write_cmd_u8(SSD1680_GATE_VOLTAGE, 0x17);
        const uint8_t src_v[3] = {0x41, 0x00, 0x32};
        epd_write_cmd_data(SSD1680_SOURCE_VOLTAGE, src_v, 3);
        epd_write_cmd_u8(SSD1680_WRITE_BORDER, 0x05);
        epd_set_ram_window_and_cursor();
    }
}

static void ssd1680_upload_planes(const uint8_t *bw_plane, const uint8_t *red_plane, uint32_t mode) {
    ssd1680_power_up(mode);

    const uint8_t y_zero[2] = {0x00, 0x00};

    if (mode == MTSC_EPD_MODE_GRAY4) {
        // 4-Grayscale encoding matching Adafruit_SSD1680_Grayscale4 (_SSD1680_GRAY4_LUT):
        //   White      (0): bw_plane=0, red_plane=0 -> inverted: RAM1 (0x24)=1, RAM2 (0x26)=1 -> L3
        //   Light Gray (1): bw_plane=1, red_plane=0 -> inverted: RAM1 (0x24)=0, RAM2 (0x26)=1 -> L2
        //   Dark Gray  (2): bw_plane=0, red_plane=1 -> inverted: RAM1 (0x24)=1, RAM2 (0x26)=0 -> L1
        //   Black      (3): bw_plane=1, red_plane=1 -> inverted: RAM1 (0x24)=0, RAM2 (0x26)=0 -> L0
        epd_write_cmd_u8(SSD1680_SET_RAMXCOUNT, 0x00);
        epd_write_cmd_data(SSD1680_SET_RAMYCOUNT, y_zero, 2);
        epd_write_cmd(SSD1680_WRITE_RAM1);
        epd_dc_data();
        for (uint32_t i = 0; i < MTSC_EPD_PLANE_BYTES; i++) {
            epd_spi_transfer_byte((uint8_t)~bw_plane[i]);
        }

        sleep_ms(2);

        epd_write_cmd_u8(SSD1680_SET_RAMXCOUNT, 0x00);
        epd_write_cmd_data(SSD1680_SET_RAMYCOUNT, y_zero, 2);
        epd_write_cmd(SSD1680_WRITE_RAM2);
        epd_dc_data();
        for (uint32_t i = 0; i < MTSC_EPD_PLANE_BYTES; i++) {
            epd_spi_transfer_byte((uint8_t)~red_plane[i]);
        }

        for (uint32_t i = 0; i < MTSC_EPD_PLANE_BYTES; i++) {
            prev_mono_ram[i] = (uint8_t)~red_plane[i];
        }
        has_prev_mono_frame = true;

        epd_write_cmd_u8(SSD1680_DISP_CTRL2, 0xC7);
        epd_write_cmd(SSD1680_MASTER_ACTIVATE);
    } else {
        epd_write_cmd_u8(SSD1680_SET_RAMXCOUNT, 0x00);
        epd_write_cmd_data(SSD1680_SET_RAMYCOUNT, y_zero, 2);

        epd_write_cmd(SSD1680_WRITE_RAM1);
        epd_dc_data();
        for (uint32_t i = 0; i < MTSC_EPD_PLANE_BYTES; i++) {
            epd_spi_transfer_byte((uint8_t)~red_plane[i]);
        }

        sleep_ms(2);

        epd_write_cmd_u8(SSD1680_SET_RAMXCOUNT, 0x00);
        epd_write_cmd_data(SSD1680_SET_RAMYCOUNT, y_zero, 2);
        if (mode == MTSC_EPD_MODE_FAST_MONO) {
            epd_write_cmd_data(SSD1680_WRITE_RAM2, prev_mono_ram, MTSC_EPD_PLANE_BYTES);
        } else {
            epd_write_cmd(SSD1680_WRITE_RAM2);
            epd_dc_data();
            for (uint32_t i = 0; i < MTSC_EPD_PLANE_BYTES; i++) {
                epd_spi_transfer_byte(0x00u);
            }
        }

        for (uint32_t i = 0; i < MTSC_EPD_PLANE_BYTES; i++) {
            prev_mono_ram[i] = (uint8_t)~red_plane[i];
        }
        has_prev_mono_frame = true;

        if (mode == MTSC_EPD_MODE_FAST_MONO) {
            epd_write_cmd_u8(SSD1680_DISP_CTRL2, 0xC7);
        } else {
            epd_write_cmd_u8(SSD1680_DISP_CTRL2, 0xF4);
        }
        epd_write_cmd(SSD1680_MASTER_ACTIVATE);
    }
}

// ============================================================================
// ThinkInk_290_Grayscale4_T5 (IL0373 / UC8151D) Driver Implementation
// ============================================================================
static void il0373_upload_planes(const uint8_t *bw_plane, const uint8_t *red_plane, uint32_t mode) {
    const uint8_t boost_soft[3] = {0x17, 0x17, 0x17};
    const uint8_t res_128x296[3] = {0x80, 0x01, 0x28}; // HRES=128 (0x80), VRES=296 (0x0128)

    if (mode == MTSC_EPD_MODE_GRAY4) {
        il0373_fast_mono_active = false;
        // Matches ti_290t5_gray4_init_code + ti_290t5_gray4_lut_code in ThinkInk_290_Grayscale4_T5.h
        const uint8_t pwr_gray4[5] = {0x03, 0x00, 0x2B, 0x2B, 0x13};
        il0373_write_cmd_data(IL0373_POWER_SETTING, pwr_gray4, 5);
        il0373_write_cmd_data(IL0373_BOOSTER_SOFT_START, boost_soft, 3);
        il0373_write_cmd(IL0373_POWER_ON);
        sleep_ms(200);

        il0373_write_cmd_u8(IL0373_PANEL_SETTING, 0x3F);
        il0373_write_cmd_u8(IL0373_PLL, 0x3C);
        il0373_write_cmd_u8(IL0373_VCM_DC_SETTING, 0x12);
        il0373_write_cmd_u8(IL0373_CDI, 0x97);

        il0373_write_cmd_data(IL0373_LUT_VCOM, il0373_gray4_lut_vcom, sizeof(il0373_gray4_lut_vcom));
        il0373_write_cmd_data(IL0373_LUT_WW,   il0373_gray4_lut_ww,   sizeof(il0373_gray4_lut_ww));
        il0373_write_cmd_data(IL0373_LUT_BW,   il0373_gray4_lut_bw,   sizeof(il0373_gray4_lut_bw));
        il0373_write_cmd_data(IL0373_LUT_WB,   il0373_gray4_lut_wb,   sizeof(il0373_gray4_lut_wb));
        il0373_write_cmd_data(IL0373_LUT_BB,   il0373_gray4_lut_bb,   sizeof(il0373_gray4_lut_bb));

        il0373_write_cmd_data(IL0373_RESOLUTION, res_128x296, 3);

        // Write DTM1 (0x10): color_buffer (~red_plane)
        epd_cs_high();
        epd_dc_cmd();
        epd_cs_low();
        uint8_t cmd_dtm1 = IL0373_DTM1;
        spi_write_blocking(EPD_SPI_PORT, &cmd_dtm1, 1);
        epd_dc_data();
        for (uint32_t i = 0; i < MTSC_EPD_PLANE_BYTES; i++) {
            uint8_t b = (uint8_t)~red_plane[i];
            spi_write_blocking(EPD_SPI_PORT, &b, 1);
        }
        epd_cs_high();

        sleep_ms(2);

        // Write DTM2 (0x13): black_buffer (~bw_plane)
        epd_cs_high();
        epd_dc_cmd();
        epd_cs_low();
        uint8_t cmd_dtm2 = IL0373_DTM2;
        spi_write_blocking(EPD_SPI_PORT, &cmd_dtm2, 1);
        epd_dc_data();
        for (uint32_t i = 0; i < MTSC_EPD_PLANE_BYTES; i++) {
            uint8_t b = (uint8_t)~bw_plane[i];
            spi_write_blocking(EPD_SPI_PORT, &b, 1);
        }
        epd_cs_high();

        // After a 4-Grayscale render (e.g. initial splash screen), force the first update of a new
        // composition to run FULL_MONO so the 4-Grayscale splash screen is cleanly wiped to white.
        has_prev_mono_frame = false;

        il0373_write_cmd(IL0373_DISPLAY_REFRESH);
    } else if (mode == MTSC_EPD_MODE_FAST_MONO) {
        const uint8_t pwr_gray4[5] = {0x03, 0x00, 0x2B, 0x2B, 0x13};
        if (!il0373_fast_mono_active) {
            il0373_write_cmd_data(IL0373_POWER_SETTING, pwr_gray4, 5);
            il0373_write_cmd_data(IL0373_BOOSTER_SOFT_START, boost_soft, 3);
            il0373_write_cmd(IL0373_POWER_ON);
            sleep_ms(100);
            il0373_fast_mono_active = true;
        }

        il0373_write_cmd_u8(IL0373_PANEL_SETTING, 0x3F);
        il0373_write_cmd_u8(IL0373_PLL, 0x3C);
        // Set VCM_DC_SETTING = 0x00 (-0.1V ~ 0V) during unipolar partial refresh so unchanged
        // White background pixels (V_source = 0V) do not see a 1.0V bias against VCOM_DC (-1.0V)
        // that temporarily dims the screen during each partial refresh.
        il0373_write_cmd_u8(IL0373_VCM_DC_SETTING, 0x00);
        // Set CDI = 0xD7 (VBD = 11b floating border) so the display border does not dim either.
        il0373_write_cmd_u8(IL0373_CDI, 0xD7);

        il0373_write_cmd_data(IL0373_LUT_VCOM, il0373_fastmono_lut_vcom, sizeof(il0373_fastmono_lut_vcom));
        il0373_write_cmd_data(IL0373_LUT_WW,   il0373_fastmono_lut_ww,   sizeof(il0373_fastmono_lut_ww));
        il0373_write_cmd_data(IL0373_LUT_BW,   il0373_fastmono_lut_bw,   sizeof(il0373_fastmono_lut_bw));
        il0373_write_cmd_data(IL0373_LUT_WB,   il0373_fastmono_lut_wb,   sizeof(il0373_fastmono_lut_wb));
        il0373_write_cmd_data(IL0373_LUT_BB,   il0373_fastmono_lut_bb,   sizeof(il0373_fastmono_lut_bb));

        il0373_write_cmd_data(IL0373_RESOLUTION, res_128x296, 3);

        // In IL0373 / UC8151D LUT mode, bit 1 = White (0xFF) and bit 0 = Black (0x00):
        // - 1 -> 0 (White -> Black) uses LUT_WB (0x23 = 0x10, drives Black)
        // - 0 -> 1 (Black -> White) uses LUT_BW (0x22 = 0x20, drives White)
        // Write DTM1 (0x10): previous monochrome frame (`prev_mono_ram`, where 1 = White, 0 = Black)
        il0373_write_cmd_data(IL0373_DTM1, prev_mono_ram, MTSC_EPD_PLANE_BYTES);

        // Write DTM2 (0x13): new monochrome frame (`~red_plane`, where 1 = White, 0 = Black)
        epd_cs_high();
        epd_dc_cmd();
        epd_cs_low();
        uint8_t cmd_dtm2 = IL0373_DTM2;
        spi_write_blocking(EPD_SPI_PORT, &cmd_dtm2, 1);
        epd_dc_data();
        for (uint32_t i = 0; i < MTSC_EPD_PLANE_BYTES; i++) {
            uint8_t new_b = (uint8_t)~red_plane[i];
            prev_mono_ram[i] = new_b;
            spi_write_blocking(EPD_SPI_PORT, &new_b, 1);
        }
        epd_cs_high();
        has_prev_mono_frame = true;

        il0373_write_cmd(IL0373_DISPLAY_REFRESH);
    } else {
        il0373_fast_mono_active = false;
        // Full monochrome refresh using the verified 0x3F LUT path with both planes = ~red_plane
        // (1,1 = pure White; 0,0 = pure Black)
        const uint8_t pwr_gray4[5] = {0x03, 0x00, 0x2B, 0x2B, 0x13};
        il0373_write_cmd_data(IL0373_POWER_SETTING, pwr_gray4, 5);
        il0373_write_cmd_data(IL0373_BOOSTER_SOFT_START, boost_soft, 3);
        il0373_write_cmd(IL0373_POWER_ON);
        sleep_ms(150);

        il0373_write_cmd_u8(IL0373_PANEL_SETTING, 0x3F);
        il0373_write_cmd_u8(IL0373_PLL, 0x3C);
        il0373_write_cmd_u8(IL0373_VCM_DC_SETTING, 0x12);
        il0373_write_cmd_u8(IL0373_CDI, 0x97);

        il0373_write_cmd_data(IL0373_LUT_VCOM, il0373_gray4_lut_vcom, sizeof(il0373_gray4_lut_vcom));
        il0373_write_cmd_data(IL0373_LUT_WW,   il0373_gray4_lut_ww,   sizeof(il0373_gray4_lut_ww));
        il0373_write_cmd_data(IL0373_LUT_BW,   il0373_gray4_lut_bw,   sizeof(il0373_gray4_lut_bw));
        il0373_write_cmd_data(IL0373_LUT_WB,   il0373_gray4_lut_wb,   sizeof(il0373_gray4_lut_wb));
        il0373_write_cmd_data(IL0373_LUT_BB,   il0373_gray4_lut_bb,   sizeof(il0373_gray4_lut_bb));

        il0373_write_cmd_data(IL0373_RESOLUTION, res_128x296, 3);

        epd_cs_high();
        epd_dc_cmd();
        epd_cs_low();
        uint8_t cmd_dtm1 = IL0373_DTM1;
        spi_write_blocking(EPD_SPI_PORT, &cmd_dtm1, 1);
        epd_dc_data();
        for (uint32_t i = 0; i < MTSC_EPD_PLANE_BYTES; i++) {
            uint8_t b = (uint8_t)~red_plane[i];
            prev_mono_ram[i] = b;
            spi_write_blocking(EPD_SPI_PORT, &b, 1);
        }
        epd_cs_high();

        sleep_ms(2);

        il0373_write_cmd_data(IL0373_DTM2, prev_mono_ram, MTSC_EPD_PLANE_BYTES);
        has_prev_mono_frame = true;

        il0373_write_cmd(IL0373_DISPLAY_REFRESH);
    }
}

// ============================================================================
// Public FFI Entry Points for Scala Native
// ============================================================================

// Configures SDCS (D5/GPIO7), SRCS (D6/GPIO8), and ECS (D9/GPIO9) when SPI0 is idle.
// - When the OLED FeatherWing (#2900) is detected on I2C1, parks D5/D6/D9 as pulled-up inputs so
//   pressing the OLED FeatherWing's physical Buttons A/B/C (wired to D9/D6/D5) never contends with
//   push-pull HIGH outputs, while 100k external pull-ups (R1, R6, R7) + RP2040 pull-ups keep CS HIGH.
// - When no OLED FeatherWing is connected, keeps D5/D6/D9 actively driven HIGH as push-pull outputs
//   so the long eInk FPC ribbon cable cannot pick up ambient EMI/ESD spikes on D9.
static void epd_park_cs_pins(void) {
    gpio_put(EPD_SDCS_PIN, true);
    gpio_put(EPD_SRCS_PIN, true);
    gpio_put(EPD_ECS_PIN, true);
    if (pico_oled_is_present()) {
        sleep_us(2);
        gpio_set_dir(EPD_SDCS_PIN, GPIO_IN);
        gpio_pull_up(EPD_SDCS_PIN);
        gpio_set_dir(EPD_SRCS_PIN, GPIO_IN);
        gpio_pull_up(EPD_SRCS_PIN);
        gpio_set_dir(EPD_ECS_PIN, GPIO_IN);
        gpio_pull_up(EPD_ECS_PIN);
    } else {
        gpio_set_dir(EPD_SDCS_PIN, GPIO_OUT);
        gpio_set_dir(EPD_SRCS_PIN, GPIO_OUT);
        gpio_set_dir(EPD_ECS_PIN, GPIO_OUT);
    }
}

// Checks if an external pull-up resistor (such as R1=100k on ECS/D9) pulls the pin HIGH
// after discharging pad capacitance LOW for 2 us.
static bool epd_probe_external_pullup(uint pin) {
    gpio_init(pin);
    gpio_set_dir(pin, GPIO_OUT);
    gpio_put(pin, false);
    sleep_us(2);
    gpio_set_dir(pin, GPIO_IN);
    gpio_disable_pulls(pin);
    sleep_us(50);
    return gpio_get(pin);
}

void pico_epd_init(void) {
    if (epd_initialized) {
        return;
    }

    // 1. Probe external 100k pull-ups on D9 (ECS, R1), D6 (SRCS, R6), D5 (SDCS, R7)
    uint32_t pull_mask = 0;
    if (epd_probe_external_pullup(EPD_ECS_PIN))  pull_mask |= 0x01u;
    if (epd_probe_external_pullup(EPD_SRCS_PIN)) pull_mask |= 0x02u;
    if (epd_probe_external_pullup(EPD_SDCS_PIN)) pull_mask |= 0x04u;

    // 2. Deselect onboard MicroSD (D5/GPIO7) and SPI SRAM (D6/GPIO8) so they never contend on SPI0
    gpio_init(EPD_SDCS_PIN);
    gpio_set_dir(EPD_SDCS_PIN, GPIO_OUT);
    gpio_put(EPD_SDCS_PIN, true);

    gpio_init(EPD_SRCS_PIN);
    gpio_set_dir(EPD_SRCS_PIN, GPIO_OUT);
    gpio_put(EPD_SRCS_PIN, true);

    // 3. Configure E-Ink ECS (D9/GPIO9) and D/C (D10/GPIO10)
    gpio_init(EPD_ECS_PIN);
    gpio_set_dir(EPD_ECS_PIN, GPIO_OUT);
    gpio_put(EPD_ECS_PIN, true);

    gpio_init(EPD_DC_PIN);
    gpio_set_dir(EPD_DC_PIN, GPIO_OUT);
    gpio_put(EPD_DC_PIN, true);

    // 3b. Read SSD1680 Status (0x2F) and IL0373 Status (0x71) with both pull-up and pull-down
    //     to detect whether SDIO is actively driven or High-Z
    uint8_t st2f_pu = 0, st2f_pd = 0;
    uint8_t st71_pu = 0, st71_pd = 0;
    epd_bitbang_read_reg_pull(SSD1680_READ_STATUS, &st2f_pu, 1, true);
    epd_bitbang_read_reg_pull(SSD1680_READ_STATUS, &st2f_pd, 1, false);
    epd_bitbang_read_reg_pull(IL0373_GET_STATUS,   &st71_pu, 1, true);
    epd_bitbang_read_reg_pull(IL0373_GET_STATUS,   &st71_pd, 1, false);

    uint8_t uid[4] = {0};
    epd_bitbang_read_reg_pull(SSD1680_READ_USERID, uid, 4, false);
    epd_diag_userid_hi = ((uint32_t)uid[0] << 24) | ((uint32_t)uid[1] << 16) | ((uint32_t)uid[2] << 8) | uid[3];
    epd_diag_userid_lo = pull_mask;
    epd_diag_status_2f =
        ((uint32_t)st2f_pu << 24) |
        ((uint32_t)st2f_pd << 16) |
        ((uint32_t)st71_pu << 8)  |
        ((uint32_t)st71_pd);

    // Auto-select SSD1680 only if 0x2F actively drives SDIO with SSD1680 Chip ID (0x10..0x13);
    // otherwise default to IL0373 / UC8151D (ThinkInk_290_Grayscale4_T5).
    if (st2f_pu == st2f_pd && st2f_pu != 0x00u && st2f_pu != 0xFFu && (st2f_pu & 0x30u) == 0x10u) {
        epd_controller_type = EPD_CTRL_SSD1680;
    } else {
        epd_controller_type = EPD_CTRL_IL0373;
    }

    // 4. Initialize SPI0 at 4 MHz on SCK=GPIO18, MOSI=GPIO19, MISO=GPIO20
    spi_init(EPD_SPI_PORT, 4 * 1000 * 1000);
    gpio_set_function(EPD_SCK_PIN, GPIO_FUNC_SPI);
    gpio_set_function(EPD_MOSI_PIN, GPIO_FUNC_SPI);
    gpio_set_function(EPD_MISO_PIN, GPIO_FUNC_SPI);

    // 4b. Probe FeatherWing's onboard 23K640 SPI SRAM (SRCS = GPIO 8)
    epd_diag_probe_sram();

    // Park SDCS (D5/GPIO7), SRCS (D6/GPIO8), and ECS (D9/GPIO9) according to whether OLED is stacked
    epd_park_cs_pins();

    // 5. Configure FeatherWing Buttons A (D11/GPIO11), B (D12/GPIO12), C (D13/GPIO13)
    //    as inputs with internal pull-ups (active LOW when pressed).
    gpio_init(EPD_BTN_A_PIN);
    gpio_set_dir(EPD_BTN_A_PIN, GPIO_IN);
    gpio_pull_up(EPD_BTN_A_PIN);

    gpio_init(EPD_BTN_B_PIN);
    gpio_set_dir(EPD_BTN_B_PIN, GPIO_IN);
    gpio_pull_up(EPD_BTN_B_PIN);

    gpio_init(EPD_BTN_C_PIN);
    gpio_set_dir(EPD_BTN_C_PIN, GPIO_IN);
    gpio_pull_up(EPD_BTN_C_PIN);

    for (uint32_t i = 0; i < MTSC_EPD_PLANE_BYTES; i++) {
        prev_mono_ram[i] = 0xFFu; // All white in inverted monochrome convention
    }
    has_prev_mono_frame = false;
    epd_initialized = true;
}

bool pico_epd_buttons_active(void) {
    return epd_initialized;
}

void pico_epd_power_off(void) {
    if (!epd_initialized || !epd_power_is_on) {
        return;
    }
    while (time_us_64() < epd_busy_until_us) {
        sleep_ms(5);
    }
    gpio_put(EPD_ECS_PIN, true);
    gpio_set_dir(EPD_ECS_PIN, GPIO_OUT);
    if (epd_controller_type == EPD_CTRL_IL0373) {
        il0373_write_cmd(IL0373_POWER_OFF);
    } else {
        epd_write_cmd_u8(SSD1680_DISP_CTRL2, 0x83);
        epd_write_cmd(SSD1680_MASTER_ACTIVATE);
    }
    epd_park_cs_pins();
    epd_power_is_on = false;
    il0373_fast_mono_active = false;
}

bool pico_epd_is_busy(void) {
    if (time_us_64() < epd_busy_until_us) {
        return true;
    }
    // Automatically shut down the eInk +/-15V charge pump once a full or 4-gray refresh finishes
    if (epd_power_is_on && !il0373_fast_mono_active) {
        pico_epd_power_off();
    }
    return false;
}

void pico_epd_set_controller(uint32_t ctrl_type) {
    if (ctrl_type == EPD_CTRL_SSD1680 || ctrl_type == EPD_CTRL_IL0373) {
        epd_controller_type = ctrl_type;
    }
}

void pico_epd_upload_planes(const uint8_t *bw_plane, const uint8_t *red_plane, uint32_t mode) {
    if (!epd_initialized || bw_plane == NULL || red_plane == NULL) {
        return;
    }

    // If a forced upload arrives while a previous waveform is still in flight,
    // wait for the previous waveform to finish so the controller accepts new SPI commands.
    while (time_us_64() < epd_busy_until_us) {
        sleep_ms(5);
    }

    // If a fast update is requested before any baseline frame has been drawn, promote to full mono
    if (mode == MTSC_EPD_MODE_FAST_MONO && !has_prev_mono_frame) {
        mode = MTSC_EPD_MODE_FULL_MONO;
    }

    epd_diag_upload_count++;
    epd_diag_last_mode = mode;

    // Activate ECS (D9/GPIO9) as push-pull output during the SPI0 transaction
    gpio_put(EPD_ECS_PIN, true);
    gpio_set_dir(EPD_ECS_PIN, GPIO_OUT);

    if (epd_controller_type == EPD_CTRL_IL0373) {
        il0373_upload_planes(bw_plane, red_plane, mode);
    } else {
        ssd1680_upload_planes(bw_plane, red_plane, mode);
    }

    // Return ECS/SRCS/SDCS to their idle state when SPI0 transfer completes
    epd_park_cs_pins();
    epd_power_is_on = true;

    // Record non-blocking busy deadline based on waveform duration
    uint64_t duration_us = 2400000ULL;
    if (mode == MTSC_EPD_MODE_FAST_MONO) {
        duration_us = 300000ULL;
    }
    epd_busy_until_us = time_us_64() + duration_us;

    // When performing a full-screen monochrome page wipe (start of Page 1, Page 2, etc.),
    // wait for the page wipe to finish so the first line of the new page types progressively
    // onto the fresh sheet instead of being swallowed while the panel is busy wiping.
    if (mode == MTSC_EPD_MODE_FULL_MONO) {
        while (time_us_64() < epd_busy_until_us) {
            sleep_ms(10);
        }
    }
}

uint32_t pico_epd_get_diag(uint32_t index) {
    switch (index) {
        case 0: return epd_diag_sram_status;
        case 1: return epd_diag_sram_readback;
        case 2: return epd_diag_userid_hi;
        case 3: return epd_diag_userid_lo;
        case 4: return epd_diag_status_2f;
        case 5: return epd_diag_upload_count;
        case 6: return epd_diag_last_mode;
        case 7: return epd_controller_type;
        default: return 0;
    }
}

static uint8_t epd_sample_raw_buttons(bool oled_btns) {
    uint8_t mask = 0;
    if (!gpio_get(EPD_BTN_A_PIN) || (oled_btns && !gpio_get(EPD_ECS_PIN))) {
        mask |= MTSC_EPD_BTN_A_MASK;
    }
    if (!gpio_get(EPD_BTN_B_PIN) || (oled_btns && !gpio_get(EPD_SRCS_PIN))) {
        mask |= MTSC_EPD_BTN_B_MASK;
    }
    if (!gpio_get(EPD_BTN_C_PIN) || (oled_btns && !gpio_get(EPD_SDCS_PIN))) {
        mask |= MTSC_EPD_BTN_C_MASK;
    }
    return mask;
}

static bool epd_wake_irq_registered = false;

static void epd_button_gpio_irq_callback(uint gpio, uint32_t events) {
    (void)gpio;
    (void)events;
    __sev();
}

bool pico_epd_any_button_low(void) {
    if (!epd_initialized) {
        return false;
    }
    return epd_sample_raw_buttons(pico_oled_is_present()) != 0;
}

void pico_epd_set_button_wake_irq(bool enable) {
    if (!epd_initialized) {
        return;
    }
    if (enable && !epd_wake_irq_registered) {
        gpio_set_irq_enabled_with_callback(
            EPD_BTN_A_PIN,
            GPIO_IRQ_EDGE_FALL,
            true,
            &epd_button_gpio_irq_callback
        );
        epd_wake_irq_registered = true;
    } else {
        gpio_set_irq_enabled(EPD_BTN_A_PIN, GPIO_IRQ_EDGE_FALL, enable);
    }
    gpio_set_irq_enabled(EPD_BTN_B_PIN, GPIO_IRQ_EDGE_FALL, enable);
    gpio_set_irq_enabled(EPD_BTN_C_PIN, GPIO_IRQ_EDGE_FALL, enable);
    if (pico_oled_is_present()) {
        gpio_set_irq_enabled(EPD_ECS_PIN,  GPIO_IRQ_EDGE_FALL, enable);
        gpio_set_irq_enabled(EPD_SRCS_PIN, GPIO_IRQ_EDGE_FALL, enable);
        gpio_set_irq_enabled(EPD_SDCS_PIN, GPIO_IRQ_EDGE_FALL, enable);
    }
}

uint32_t pico_epd_poll_buttons(void) {
    if (!epd_initialized) {
        return 0;
    }

    bool oled_btns = pico_oled_is_present();
    uint8_t cur_mask = epd_sample_raw_buttons(oled_btns);

    // Multi-sample glitch rejection filter: if any button pin reads LOW, verify that it remains
    // continuously LOW across 8 consecutive samples spaced by 500 us (4 ms total hold verification).
    // Costs 0 us when no button is pressed (cur_mask == 0), and rejects single-sample EMI/ESD spikes.
    if (cur_mask != 0) {
        for (int s = 0; s < 8 && cur_mask != 0; s++) {
            sleep_us(500);
            cur_mask &= epd_sample_raw_buttons(oled_btns);
        }
    }

    uint64_t now = time_us_64();
    uint32_t pressed_edges = 0;
    const uint8_t masks[3] = {MTSC_EPD_BTN_A_MASK, MTSC_EPD_BTN_B_MASK, MTSC_EPD_BTN_C_MASK};
    for (int i = 0; i < 3; i++) {
        uint8_t m = masks[i];
        bool is_down = (cur_mask & m) != 0;
        bool was_down = (prev_btn_mask & m) != 0;
        if (is_down && !was_down && (now - last_btn_edge_us[i]) > 45000ULL) {
            pressed_edges |= m;
            last_btn_edge_us[i] = now;
        } else if (!is_down && was_down && (now - last_btn_edge_us[i]) > 45000ULL) {
            last_btn_edge_us[i] = now;
        }
    }
    prev_btn_mask = cur_mask;
    return pressed_edges;
}
