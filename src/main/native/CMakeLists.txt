cmake_minimum_required(VERSION 3.10)

project(kdoomgeneric)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(JNI REQUIRED)
include_directories(${JNI_INCLUDE_DIRS})

set(SOURCE_FILES src/kdoomgeneric.cpp)

add_library(${PROJECT_NAME} SHARED ${SOURCE_FILES})

include_directories(libraries/doomgeneric/doomgeneric headers)

target_link_libraries(
        ${PROJECT_NAME}
        PRIVATE
        ${JNI_LIBRARIES}
        "libraries/doomgeneric/doomgeneric/libdoomgeneric.a"
)

set_target_properties(${PROJECT_NAME} PROPERTIES
        PREFIX "lib"
        OUTPUT_NAME "${PROJECT_NAME}"
)
