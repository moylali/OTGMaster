cmake_minimum_required(VERSION 3.4.1)

add_library( errno-lib
        SHARED
        src/c/errno.c )

add_library( usb-lib
        SHARED
        src/c/usb.c )

target_link_options(errno-lib PRIVATE "-Wl,-z,max-page-size=16384")
target_link_options(usb-lib PRIVATE "-Wl,-z,max-page-size=16384")

target_link_libraries(usb-lib log)