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

#define RXNE (1 << 5)

static void set_actuator(uint8_t setpoint) {
  TIM1->CCR1 = setpoint;
}

static uint8_t recv(uint8_t* buf, uint32_t timeout) {
  uint32_t tick1 = HAL_GetTick();
  while (!(USART2->ISR & RXNE)) {
    uint32_t tick2 = HAL_GetTick();
    if (tick2 - tick1 >= timeout) return 0;
  }
  *buf = USART2->RDR;
  return 1;
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

  // main loop
  while (1) {
    uint8_t command;
    next_message:
    while (!recv(&command, TIMEOUT_SHUTOFF)) {
      set_actuator(0);
    }
    // Retransmit command
    HAL_UART_Transmit(&huart1, &command, 1, HAL_MAX_DELAY);
    HAL_UART_Transmit(&huart2, &command, 1, HAL_MAX_DELAY);

    switch (command) {
      case COMMAND_SHUTOFF: {
        set_actuator(0);
      }

      case COMMAND_SETTREE: {
        // First byte is our setpoint
        uint8_t setpoint;
        if (!recv(&setpoint, TIMEOUT_MESSAGE)) goto next_message;
        set_actuator(setpoint);

        // Then alternate bytes to children
        uint8_t child = 0;
        while (1) {
          uint8_t childbyte;
          if (!recv(&childbyte, TIMEOUT_MESSAGE)) goto next_message;
          HAL_UART_Transmit(child ? &huart2 : &huart1, &childbyte, 1, HAL_MAX_DELAY);
          child = !child;
        }
      }
    }
  }
}
