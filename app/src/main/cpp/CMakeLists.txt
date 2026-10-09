cmake_minimum_required(VERSION 3.22)
project(shrinker_native C)

include(ExternalProject)

# mozjpeg 4.1.5 — the same encoder, at the same version, as the macOS app's
# bundled cjpeg. Built from the vendored release tarball, verified by hash.
set(MOZJPEG_ARCHIVE "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/mozjpeg/mozjpeg-4.1.5.tar.gz")
set(MOZJPEG_SHA256 9fcbb7171f6ac383f5b391175d6fb3acde5e64c4c4727274eade84ed0998fcc1)

# The arm SIMD paths are NEON intrinsics and need nothing extra. The x86 ones
# need the nasm assembler, which the NDK doesn't ship; x86 only matters for
# emulators and ChromeOS, so those builds use the plain C paths, which produce
# identical output, only more slowly.
if(ANDROID_ABI MATCHES "^x86")
    set(MOZJPEG_SIMD OFF)
else()
    set(MOZJPEG_SIMD ON)
endif()

# mozjpeg's own CMake uses CMAKE_SOURCE_DIR, so it can't be add_subdirectory'd;
# it builds as a separate project with the same NDK toolchain instead.
ExternalProject_Add(mozjpeg
    URL "${MOZJPEG_ARCHIVE}"
    URL_HASH SHA256=${MOZJPEG_SHA256}
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
    CMAKE_ARGS
        -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
        -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
        -DANDROID_ABI=${ANDROID_ABI}
        -DANDROID_PLATFORM=${ANDROID_PLATFORM}
        -DCMAKE_BUILD_TYPE=Release
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON
        -DENABLE_SHARED=OFF
        -DENABLE_STATIC=ON
        -DPNG_SUPPORTED=OFF
        -DWITH_TURBOJPEG=OFF
        -DWITH_SIMD=${MOZJPEG_SIMD}
    BUILD_COMMAND ${CMAKE_COMMAND} --build <BINARY_DIR> --target jpeg-static
    INSTALL_COMMAND ""
    BUILD_BYPRODUCTS <BINARY_DIR>/libjpeg.a
)
ExternalProject_Get_Property(mozjpeg SOURCE_DIR BINARY_DIR)

add_library(mozjpeg_static STATIC IMPORTED)
set_target_properties(mozjpeg_static PROPERTIES IMPORTED_LOCATION "${BINARY_DIR}/libjpeg.a")
add_dependencies(mozjpeg_static mozjpeg)

add_library(shrinkerjpeg SHARED mozjpeg_encode.c mozjpeg_jni.c)
# jpeglib.h lives in the source tree; jconfig.h is generated into the build tree.
target_include_directories(shrinkerjpeg PRIVATE "${SOURCE_DIR}" "${BINARY_DIR}")
target_link_libraries(shrinkerjpeg PRIVATE mozjpeg_static jnigraphics)
target_compile_options(shrinkerjpeg PRIVATE -Wall -Wextra -Werror)
# 16 KB page alignment, which Google Play requires for native libraries.
target_link_options(shrinkerjpeg PRIVATE "-Wl,-z,max-page-size=16384")
