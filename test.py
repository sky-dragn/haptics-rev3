from serial import Serial
import time

s = Serial("/dev/tty.usbmodem102", 115200)


while True:
  for d in range(16):
    c = bytearray(16)
    c[d] = 0xff
    s.write(bytes([0x01]) + c)
    time.sleep(0.1)
