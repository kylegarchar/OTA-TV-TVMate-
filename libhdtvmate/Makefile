CFLAGS ?= -O2 -Wall -Wextra
USB_CFLAGS := $(shell pkg-config --cflags libusb-1.0)
USB_LIBS := $(shell pkg-config --libs libusb-1.0)

all: hdtvmate-cli libhdtvmate.a

libhdtvmate.a: hdtvmate.o
	$(AR) rcs $@ $^
hdtvmate.o: hdtvmate.c hdtvmate.h
	$(CC) $(CFLAGS) $(USB_CFLAGS) -c hdtvmate.c
hdtvmate-cli: hdtvmate-cli.c libhdtvmate.a
	$(CC) $(CFLAGS) $(USB_CFLAGS) -o $@ hdtvmate-cli.c libhdtvmate.a $(USB_LIBS)
clean:
	rm -f *.o *.a hdtvmate-cli
