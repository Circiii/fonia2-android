# The Whisper runtime behind :speech: whisper.cpp v1.9.5 (pinned submodule, compiled unchanged) on ggml's
# CPU backend, plus the JNI bridge. iOS runs the same model through WhisperKit and Core ML.
#
# Release flags whatever the Gradle variant: a -O0 ggml is far too slow to test with.
#
# arm64-v8a ships two builds of the same code, picked at run time by WhisperCppNative from the CPU's
# feature list, so that ARMv8.0 phones (Cortex-A53/A73) never execute an instruction they lack:
#   libfonia_whisper.so          -march=armv8-a             NEON (baseline of every arm64 phone)
#   libfonia_whisper_dotprod.so  -march=armv8.2-a+dotprod   NEON + SDOT/UDOT, when /proc/cpuinfo has asimddp
# Neither enables +fp16 (FP16 vector arithmetic): ggml would then accumulate the f16 dot products in
# half precision, which moves the log-probabilities the judges read (see whisper/PARITY.md).
# x86_64 exists for the emulator only: AVX2 + FMA + F16C, so it needs an AVX2 host.

cmake_minimum_required(VERSION 3.22.1)
project(fonia_whisper C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)
set(FONIA_WHISPER_VERSION "1.9.5")

include(ExternalProject)

# ggml (core + CPU backend) as static PIC libraries, built by ggml's own CMake from the submodule.
# One build per instruction set: ggml's targets have fixed names, so a second configuration of the
# same sources needs its own CMake project.
function(fonia_add_ggml tag)
    set(bin ${CMAKE_CURRENT_BINARY_DIR}/ggml-${tag})
    ExternalProject_Add(ggml_build_${tag}
        SOURCE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/ggml-variant
        BINARY_DIR ${bin}
        CMAKE_ARGS
            -DFONIA_GGML_DIR=${WHISPER_DIR}/ggml
            -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
            -DANDROID_ABI=${ANDROID_ABI}
            -DANDROID_PLATFORM=${ANDROID_PLATFORM}
            -DANDROID_STL=${ANDROID_STL}
            -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
            -DCMAKE_BUILD_TYPE=Release
            -DCMAKE_POSITION_INDEPENDENT_CODE=ON
            -DBUILD_SHARED_LIBS=OFF
            -DGGML_NATIVE=OFF
            -DGGML_OPENMP=OFF
            -DGGML_LLAMAFILE=OFF
            -DGGML_CCACHE=OFF
            -DGGML_BUILD_TESTS=OFF
            -DGGML_BUILD_EXAMPLES=OFF
            ${ARGN}
        INSTALL_COMMAND ""
        BUILD_BYPRODUCTS
            ${bin}/ggml/src/libggml.a
            ${bin}/ggml/src/libggml-cpu.a
            ${bin}/ggml/src/libggml-base.a
    )
    foreach(lib ggml ggml-cpu ggml-base)
        add_library(${lib}_${tag} STATIC IMPORTED GLOBAL)
        set_target_properties(${lib}_${tag} PROPERTIES IMPORTED_LOCATION ${bin}/ggml/src/lib${lib}.a)
        add_dependencies(${lib}_${tag} ggml_build_${tag})
    endforeach()
endfunction()

find_library(LOG_LIB log)

# libfonia_whisper*.so: whisper.cpp compiled from a unity file that also holds the decode step with
# cross-attention capture (fonia_whisper.cpp), and the JNI functions (fonia_jni.cpp).
function(fonia_add_whisper name tag)
    add_library(${name} SHARED
        fonia_whisper.cpp
        fonia_jni.cpp
        ${WHISPER_DIR}/src/aneforge/whisper-aneforge.cpp
    )
    target_include_directories(${name} PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${WHISPER_DIR}/include
        ${WHISPER_DIR}/src
        ${WHISPER_DIR}/ggml/include
    )
    target_compile_definitions(${name} PRIVATE WHISPER_VERSION="${FONIA_WHISPER_VERSION}" NDEBUG)
    target_compile_options(${name} PRIVATE
        -O3
        -fvisibility=hidden -fvisibility-inlines-hidden
        -ffunction-sections -fdata-sections
        -Wno-unused-function -Wno-deprecated-declarations
        ${ARGN}
    )
    target_link_options(${name} PRIVATE
        -Wl,--gc-sections
        -Wl,--exclude-libs,ALL
        -Wl,-z,max-page-size=16384
    )
    # Archive order: whisper -> ggml (backend registry) -> ggml-cpu -> ggml-base.
    target_link_libraries(${name} PRIVATE ggml_${tag} ggml-cpu_${tag} ggml-base_${tag} ${LOG_LIB} android dl m)
endfunction()

if (ANDROID_ABI STREQUAL "arm64-v8a")
    fonia_add_ggml(armv8 -DGGML_CPU_ARM_ARCH=armv8-a)
    fonia_add_ggml(dotprod -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod)
    fonia_add_whisper(fonia_whisper armv8 -march=armv8-a)
    fonia_add_whisper(fonia_whisper_dotprod dotprod -march=armv8.2-a+dotprod)
elseif (ANDROID_ABI STREQUAL "x86_64")
    fonia_add_ggml(x86 -DGGML_SSE42=ON -DGGML_AVX=ON -DGGML_AVX2=ON -DGGML_FMA=ON -DGGML_F16C=ON -DGGML_BMI2=ON)
    fonia_add_whisper(fonia_whisper x86 -msse4.2 -mavx -mavx2 -mfma -mf16c -mbmi2)
else()
    message(FATAL_ERROR "fonia_whisper: unsupported ABI ${ANDROID_ABI} (arm64-v8a and x86_64 only)")
endif()
