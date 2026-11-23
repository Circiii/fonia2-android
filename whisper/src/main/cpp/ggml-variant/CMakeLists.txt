# One configuration of ggml, built as an ExternalProject by ../CMakeLists.txt. ggml is added as a
# subdirectory, as whisper.cpp itself adds it: the copy vendored in whisper.cpp cannot be the top-level
# project (it lacks the ggml.pc.in a standalone ggml build installs).
cmake_minimum_required(VERSION 3.22.1)
project(fonia_ggml C CXX)

add_subdirectory(${FONIA_GGML_DIR} ggml)
