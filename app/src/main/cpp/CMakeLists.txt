cmake_minimum_required(VERSION 3.22.1)
project(autoball LANGUAGES C CXX)

# QuickJS 源码由 scripts/fetch_quickjs.sh 拉取。
# 缺失时编译 stub：JNI 符号保持一致，JS 脚本返回"引擎不可用"，动作流仍可完整运行。
set(AUTOBALL_QJS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/quickjs)
if(EXISTS ${AUTOBALL_QJS_DIR}/quickjs.h)
    set(HAS_QUICKJS TRUE)
    message(STATUS "[autoball] QuickJS 源码存在，编译原生引擎")
else()
    set(HAS_QUICKJS FALSE)
    message(WARNING "[autoball] 未找到 QuickJS 源码，编译 stub 引擎（JS 脚本不可用，动作流不受影响）")
endif()

add_library(autoball SHARED autoball_quickjs.cpp)

if(HAS_QUICKJS)
    target_include_directories(autoball PRIVATE ${AUTOBALL_QJS_DIR})
    target_sources(autoball PRIVATE
            ${AUTOBALL_QJS_DIR}/quickjs.c
            ${AUTOBALL_QJS_DIR}/cutils.c
            ${AUTOBALL_QJS_DIR}/libregexp.c
            ${AUTOBALL_QJS_DIR}/libunicode.c
            ${AUTOBALL_QJS_DIR}/libbf.c)
    target_compile_definitions(autoball PRIVATE
            AUTOBALL_HAS_QUICKJS=1
            CONFIG_BIGNUM=1
            CONFIG_VERSION=\"2024-01-13\"
            _GNU_SOURCE=1)
    # -Oz 控制 so 体积；QuickJS 单 ABI 目标约 350KB
    target_compile_options(autoball PRIVATE -Oz -fvisibility=hidden -fno-exceptions)
    target_compile_options(autoball PRIVATE $<$<COMPILE_LANGUAGE:C>:-Wno-unused-function>)
else()
    target_compile_definitions(autoball PRIVATE AUTOBALL_HAS_QUICKJS=0)
endif()

target_link_libraries(autoball log android m)
