#include <stdint.h>
#include "main.h"
#include "stm32g0xx_hal.h"
#include "stm32g0xx_hal_def.h"
#include "stm32g0xx_hal_tim.h"
#include "stm32g0xx_hal_uart.h"

extern UART_HandleTypeDef huart1;
extern UART_HandleTypeDef huart2;
extern TIM_HandleTypeDef htim1;

// 5 ms gap means end of message (bytes should be 1 ms at most apart)
#define TIMEOUT_MESSAGE 10
// If no messages received in 1 second then turn off actuators to prevent overheat
#define TIMEOUT_SHUTOFF 1000

#define COMMAND_SHUTOFF 0x00
#define COMMAND_SETTREE 0x01
#define COMMAND_SETINTR 0x02

#define RXNE (1 << 5)
#define TXE (1 << 7)

_Atomic uint8_t setpoint = 0;
_Atomic uint8_t ramp = 255;

void HAL_TIM_PeriodElapsedCallback(TIM_HandleTypeDef *htim) {
  if (htim != &htim1) return;

  // ramp toward setpoint at ramp rate
  int32_t x = TIM1->CCR1;
  if (x < setpoint) {
    x += ramp;
    if (x > setpoint) x = setpoint;
  } else if (x > setpoint) {
    x -= ramp;
    if (x < setpoint) x = setpoint;
  }

  TIM1->CCR1 = x;
}

static uint8_t rx(uint8_t* buf, uint32_t timeout) {
  uint32_t tick1 = HAL_GetTick();
  while (!(USART2->ISR & RXNE)) {
    uint32_t tick2 = HAL_GetTick();
    if (tick2 - tick1 >= timeout) return 0;
  }
  *buf = USART2->RDR;
  return 1;
}

static void tx(uint8_t uart, uint8_t byte) {
  USART_TypeDef* usart = uart ? USART2 : USART1;
  while (!(usart->ISR & TXE));
  usart->TDR = byte;
}

void real_main(void) {
  // init pwm pin
  GPIO_InitTypeDef pin;
  pin.Pin = 1 << 8;
  pin.Mode = GPIO_MODE_AF_PP;
  pin.Alternate = GPIO_AF2_TIM1;
  pin.Pull = GPIO_NOPULL;
  pin.Speed = GPIO_SPEED_FREQ_LOW;
  HAL_GPIO_Init(GPIOA, &pin);

  // init pwm timer
  HAL_TIM_PWM_Start(&htim1, TIM_CHANNEL_1);
  __HAL_TIM_ENABLE_IT(&htim1, TIM_IT_UPDATE);

  // main loop
  while (1) {
    uint8_t command;
    while (!rx(&command, TIMEOUT_SHUTOFF)) {
      setpoint = 0;
    }
    // Retransmit command
    tx(0, command);
    tx(1, command);

    switch (command) {
      case COMMAND_SHUTOFF: {
        setpoint = 0;
        break;
      }

      case COMMAND_SETTREE: {
        // First byte is our setpoint
        uint8_t new_setpoint;
        if (!rx(&new_setpoint, TIMEOUT_MESSAGE)) break;
        setpoint = new_setpoint;

        // Then alternate bytes to children
        uint8_t child = 0;
        while (1) {
          uint8_t childbyte;
          if (!rx(&childbyte, TIMEOUT_MESSAGE)) break;
          tx(child, childbyte);
          child = !child;
        }
        break;
      }

      case COMMAND_SETINTR: {
        // First byte is ramp value, retransmit
        uint8_t new_ramp;
        if (!rx(&new_ramp, TIMEOUT_MESSAGE)) break;
        ramp = new_ramp;
        tx(0, ramp);
        tx(1, ramp);
        break;
      }
    }
  }
}
