cmake_minimum_required(VERSION 3.22.1)
project(fonia_audio_rt CXX)

include(CheckCXXCompilerFlag)

# Oboe comes from its AAR through prefab (build.gradle.kts: buildFeatures.prefab).
find_package(oboe REQUIRED CONFIG)

add_library(fonia_audio_rt SHARED
    FoniaAudioRT.cpp
    FoniaCapture.cpp
    FoniaJni.cpp
)

target_compile_features(fonia_audio_rt PRIVATE cxx_std_17)
target_compile_options(fonia_audio_rt PRIVATE -Wall -Wextra -Werror)

# iOS builds FoniaAudioRT with -Werror=function-effects. Clang learns the nonblocking attribute in
# version 20; with NDK r28 (Clang 19) the check is off and the real-time rules are kept by review.
check_cxx_compiler_flag(-Wfunction-effects FONIA_HAS_FUNCTION_EFFECTS)
if (FONIA_HAS_FUNCTION_EFFECTS)
    target_compile_options(fonia_audio_rt PRIVATE -Wfunction-effects -Werror=function-effects)
endif ()

# NDK r28 already aligns to 16 KB; saying so keeps that true with an older NDK.
target_link_options(fonia_audio_rt PRIVATE "-Wl,-z,max-page-size=16384")

target_link_libraries(fonia_audio_rt PRIVATE oboe::oboe log)
