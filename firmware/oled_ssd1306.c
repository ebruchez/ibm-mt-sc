#include "oled_ssd1306.h"

#include <string.h>
#include "pico/stdlib.h"
#include "hardware/gpio.h"
#include "hardware/i2c.h"

// ============================================================================
// Adafruit Feather RP2040 + 128x32 OLED FeatherWing (#2900, SSD1306) Pinout
// ============================================================================
#define OLED_I2C_PORT   i2c1
#define OLED_SDA_PIN    2   // Feather SDA -> RP2040 GPIO 2 (I2C1 SDA)
#define OLED_SCL_PIN    3   // Feather SCL -> RP2040 GPIO 3 (I2C1 SCL)
#define OLED_I2C_ADDR   0x3Cu
#define OLED_BAUDRATE   400000u // 400 kHz Fast-Mode I2C (~11 ms for 512B, rock-solid with internal/external pull-ups)

// Bounded timeouts so an unconnected or unplugged OLED never blocks the emulator
#define OLED_PROBE_TIMEOUT_US  2500u  // 2.5 ms timeout for initial 0x3C ACK probe
#define OLED_CMD_TIMEOUT_US    5000u  // 5 ms timeout for short command packets
#define OLED_DATA_TIMEOUT_US   25000u // 25 ms timeout for 513-byte framebuffer burst

// SSD1306 Command Definitions
#define SSD1306_DISPLAYOFF          0xAE
#define SSD1306_DISPLAYON           0xAF
#define SSD1306_SETDISPLAYCLOCKDIV  0xD5
#define SSD1306_SETMULTIPLEX        0xA8
#define SSD1306_SETDISPLAYOFFSET    0xD3
#define SSD1306_SETSTARTLINE        0x40
#define SSD1306_CHARGEPUMP          0x8D
#define SSD1306_MEMORYMODE          0x20
#define SSD1306_SEGREMAP_NORMAL     0xA1
#define SSD1306_SEGREMAP_FLIP       0xA0
#define SSD1306_COMSCANDEC_NORMAL   0xC8
#define SSD1306_COMSCANINC_FLIP     0xC0
#define SSD1306_SETCOMPINS          0xDA
#define SSD1306_SETCONTRAST         0x81
#define SSD1306_SETPRECHARGE        0xD9
#define SSD1306_SETVCOMDETECT       0xDB
#define SSD1306_DISPLAYALLON_RESUME 0xA4
#define SSD1306_NORMALDISPLAY       0xA6
#define SSD1306_DEACTIVATE_SCROLL   0x2E
#define SSD1306_COLUMNADDR          0x21
#define SSD1306_PAGEADDR            0x22

static bool oled_bus_initialized = false;
static bool oled_present = false;
static bool oled_awake = false;
static bool oled_flip180 = false;
static uint32_t oled_diag_frames = 0;
static int32_t oled_diag_last_rc = 0;

// Pre-allocated 513-byte I2C burst buffer (0x40 data control byte + 512 framebuffer bytes)
static uint8_t oled_tx_buf[1 + MTSC_OLED_BUFFER_BYTES];

static bool oled_write_cmd_list(const uint8_t *cmds, size_t len) {
    uint8_t buf[32];
    if (len + 1 > sizeof(buf)) {
        return false;
    }
    buf[0] = 0x00u; // Co = 0, D/C# = 0 (command stream)
    memcpy(&buf[1], cmds, len);
    int rc = i2c_write_timeout_us(OLED_I2C_PORT, OLED_I2C_ADDR, buf, len + 1, false, OLED_CMD_TIMEOUT_US);
    oled_diag_last_rc = rc;
    return rc == (int)(len + 1);
}

void pico_oled_init(void) {
    if (!oled_bus_initialized) {
        // 1. Enable internal pull-ups on SDA (GPIO 2) and SCL (GPIO 3) BEFORE initializing I2C
        //    so that if no OLED FeatherWing is connected, the lines idle HIGH (3.3V) and never
        //    look like a stuck-LOW bus.
        gpio_init(OLED_SDA_PIN);
        gpio_set_dir(OLED_SDA_PIN, GPIO_IN);
        gpio_pull_up(OLED_SDA_PIN);

        gpio_init(OLED_SCL_PIN);
        gpio_set_dir(OLED_SCL_PIN, GPIO_IN);
        gpio_pull_up(OLED_SCL_PIN);
        sleep_us(20);

        // If either SDA or SCL is externally shorted LOW, abort I2C init safely
        if (!gpio_get(OLED_SDA_PIN) || !gpio_get(OLED_SCL_PIN)) {
            oled_present = false;
            oled_diag_last_rc = -100;
            return;
        }

        i2c_init(OLED_I2C_PORT, OLED_BAUDRATE);
        gpio_set_function(OLED_SDA_PIN, GPIO_FUNC_I2C);
        gpio_set_function(OLED_SCL_PIN, GPIO_FUNC_I2C);
        gpio_pull_up(OLED_SDA_PIN);
        gpio_pull_up(OLED_SCL_PIN);
        oled_bus_initialized = true;
    }

    // 2. Non-blocking probe of SSD1306 at 0x3C using a 2-byte NOP/status command with 2.5 ms timeout
    const uint8_t probe[2] = {0x00u, 0xE3u}; // 0xE3 = SSD1306 NOP
    int rc = i2c_write_timeout_us(OLED_I2C_PORT, OLED_I2C_ADDR, probe, sizeof(probe), false, OLED_PROBE_TIMEOUT_US);
    oled_diag_last_rc = rc;
    if (rc != (int)sizeof(probe)) {
        oled_present = false;
        return;
    }

    // 3. Configure 128x32 SSD1306 (matching Adafruit_SSD1306 128x32 I2C init sequence)
    const uint8_t init_cmds[] = {
        SSD1306_DISPLAYOFF,
        SSD1306_SETDISPLAYCLOCKDIV, 0x80,
        SSD1306_SETMULTIPLEX,       (uint8_t)(MTSC_OLED_HEIGHT - 1), // 0x1F (31)
        SSD1306_SETDISPLAYOFFSET,   0x00,
        SSD1306_SETSTARTLINE | 0x0,
        SSD1306_CHARGEPUMP,         0x14, // Internal VCC charge pump enabled
        SSD1306_MEMORYMODE,         0x00, // Horizontal addressing mode
        (uint8_t)(oled_flip180 ? SSD1306_SEGREMAP_FLIP : SSD1306_SEGREMAP_NORMAL),
        (uint8_t)(oled_flip180 ? SSD1306_COMSCANINC_FLIP : SSD1306_COMSCANDEC_NORMAL),
        SSD1306_SETCOMPINS,         0x02, // 128x32 sequential COM pin config
        SSD1306_SETCONTRAST,        0x8F,
        SSD1306_SETPRECHARGE,       0xF1,
        SSD1306_SETVCOMDETECT,      0x40,
        SSD1306_DISPLAYALLON_RESUME,
        SSD1306_NORMALDISPLAY,
        SSD1306_DEACTIVATE_SCROLL,
        SSD1306_DISPLAYON
    };

    if (!oled_write_cmd_list(init_cmds, sizeof(init_cmds))) {
        oled_present = false;
        oled_awake = false;
        return;
    }

    oled_present = true;
    oled_awake = true;
}

bool pico_oled_is_present(void) {
    return oled_present;
}

void pico_oled_set_power(bool on) {
    if (!oled_present || oled_awake == on) {
        return;
    }
    if (on) {
        const uint8_t wake_cmds[5] = {
            SSD1306_CHARGEPUMP, 0x14,
            SSD1306_MEMORYMODE, 0x00,
            SSD1306_DISPLAYON
        };
        if (!oled_write_cmd_list(wake_cmds, sizeof(wake_cmds))) {
            oled_present = false;
            oled_awake = false;
            return;
        }
        oled_awake = true;
    } else {
        const uint8_t sleep_cmd = SSD1306_DISPLAYOFF;
        if (!oled_write_cmd_list(&sleep_cmd, 1)) {
            oled_present = false;
            oled_awake = false;
            return;
        }
        oled_awake = false;
    }
}

void pico_oled_set_flip(bool flip180) {
    oled_flip180 = flip180;
    if (!oled_present) {
        return;
    }
    const uint8_t flip_cmds[2] = {
        (uint8_t)(oled_flip180 ? SSD1306_SEGREMAP_FLIP : SSD1306_SEGREMAP_NORMAL),
        (uint8_t)(oled_flip180 ? SSD1306_COMSCANINC_FLIP : SSD1306_COMSCANDEC_NORMAL)
    };
    if (!oled_write_cmd_list(flip_cmds, sizeof(flip_cmds))) {
        oled_present = false;
        oled_awake = false;
    }
}

void pico_oled_flush(const uint8_t *frame_buf) {
    if (!oled_present || frame_buf == NULL) {
        return;
    }
    if (!oled_awake) {
        pico_oled_set_power(true);
        if (!oled_present) {
            return;
        }
    }

    const uint8_t win_cmds[6] = {
        SSD1306_COLUMNADDR, 0, (uint8_t)(MTSC_OLED_WIDTH - 1),
        SSD1306_PAGEADDR,   0, (uint8_t)(MTSC_OLED_PAGES - 1)
    };
    if (!oled_write_cmd_list(win_cmds, sizeof(win_cmds))) {
        oled_present = false;
        return;
    }

    oled_tx_buf[0] = 0x40u; // Co = 0, D/C# = 1 (GDRAM data stream)
    memcpy(&oled_tx_buf[1], frame_buf, MTSC_OLED_BUFFER_BYTES);

    int rc = i2c_write_timeout_us(
        OLED_I2C_PORT,
        OLED_I2C_ADDR,
        oled_tx_buf,
        sizeof(oled_tx_buf),
        false,
        OLED_DATA_TIMEOUT_US
    );
    oled_diag_last_rc = rc;
    if (rc != (int)sizeof(oled_tx_buf)) {
        oled_present = false;
        return;
    }
    oled_diag_frames++;
}

uint32_t pico_oled_get_diag(uint32_t index) {
    switch (index) {
        case 0: return oled_present ? 1u : 0u;
        case 1: return oled_diag_frames;
        case 2: return (uint32_t)oled_diag_last_rc;
        default: return 0u;
    }
}
