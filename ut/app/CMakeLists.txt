# SPDX-License-Identifier: Apache-2.0
# Fotoz for Ubuntu Touch / Linux: Qt Quick shell over libfotozcore.so (ut/bridge, Kotlin/Native).
#
#   Click (Clickable):  clickable build --arch arm64     (see clickable.yaml, docs/handoff/UT-PORT.md)
#   Desktop (Qt 5.15 or Qt 6):
#       cmake -S ut/app -B build/ut-app -DFOTOZ_BUILD_TESTS=ON && cmake --build build/ut-app
#
# FOTOZ_CORE_LIB: path of the libfotozcore.so to ship next to the binary (click) / use in tests.
# It is built separately by Gradle (-Pfotoz.native) because a Kotlin/Native toolchain does not
# belong inside the Clickable container.
cmake_minimum_required(VERSION 3.16)
project(fotoz VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)
set(CMAKE_AUTORCC ON)

option(FOTOZ_BUILD_TESTS "Build the QtTest suite (needs a fake core + Qt Test)" OFF)
set(FOTOZ_CORE_LIB "" CACHE FILEPATH "libfotozcore.so to bundle (click) or load (tests)")

find_package(QT NAMES Qt6 Qt5 REQUIRED COMPONENTS Core)
find_package(Qt${QT_VERSION_MAJOR} REQUIRED COMPONENTS Core Gui Qml Quick QuickControls2)

add_library(fotozshell STATIC src/CoreApi.cpp src/Models.cpp src/LibraryController.cpp)
target_include_directories(fotozshell PUBLIC src)
target_link_libraries(fotozshell PUBLIC Qt${QT_VERSION_MAJOR}::Core Qt${QT_VERSION_MAJOR}::Gui ${CMAKE_DL_LIBS})
find_package(Threads REQUIRED)
target_link_libraries(fotozshell PUBLIC Threads::Threads)

add_executable(fotoz src/main.cpp qml.qrc)
target_compile_definitions(fotoz PRIVATE FOTOZ_VERSION="${PROJECT_VERSION}")
target_link_libraries(fotoz PRIVATE fotozshell Qt${QT_VERSION_MAJOR}::Qml Qt${QT_VERSION_MAJOR}::Quick Qt${QT_VERSION_MAJOR}::QuickControls2)
# The core sits in <install>/lib next to the binary.
set_target_properties(fotoz PROPERTIES INSTALL_RPATH "$ORIGIN/lib" BUILD_WITH_INSTALL_RPATH ON)

# ---- click packaging --------------------------------------------------------------------
# Clickable's cmake builder runs `make DESTDIR=<install_dir> install` with CMAKE_INSTALL_PREFIX=/.
# so everything lands at the click root.
if(NOT DEFINED CLICK_ARCH)
    if(CMAKE_LIBRARY_ARCHITECTURE MATCHES "aarch64")
        set(CLICK_ARCH arm64)
    elseif(CMAKE_LIBRARY_ARCHITECTURE MATCHES "x86_64")
        set(CLICK_ARCH amd64)
    else()
        set(CLICK_ARCH armhf)
    endif()
endif()
# Clickable passes these from clickable.yaml's `framework` (the one place that decides the OS target);
# the defaults below only matter for a plain desktop cmake run.
if(NOT CLICK_FRAMEWORK)
    set(CLICK_FRAMEWORK "ubuntu-touch-24.04-1.x")
endif()
if(NOT CLICK_POLICY_VERSION)
    set(CLICK_POLICY_VERSION "2404.1")
endif()
set(EXEC "fotoz")

configure_file(manifest.json.in ${CMAKE_CURRENT_BINARY_DIR}/manifest.json @ONLY)
configure_file(fotoz.desktop.in ${CMAKE_CURRENT_BINARY_DIR}/fotoz.desktop @ONLY)
configure_file(fotoz.apparmor.in ${CMAKE_CURRENT_BINARY_DIR}/fotoz.apparmor @ONLY)

install(TARGETS fotoz RUNTIME DESTINATION ${CMAKE_INSTALL_PREFIX})
install(FILES ${CMAKE_CURRENT_BINARY_DIR}/manifest.json
              ${CMAKE_CURRENT_BINARY_DIR}/fotoz.desktop
              ${CMAKE_CURRENT_BINARY_DIR}/fotoz.apparmor
              assets/icon.svg
        DESTINATION ${CMAKE_INSTALL_PREFIX})
if(FOTOZ_CORE_LIB)
    install(FILES ${FOTOZ_CORE_LIB} DESTINATION ${CMAKE_INSTALL_PREFIX}/lib)
else()
    message(WARNING "FOTOZ_CORE_LIB not set: the installed app will start but show 'core library could not be loaded'.")
endif()

# ---- tests -----------------------------------------------------------------------------
if(FOTOZ_BUILD_TESTS)
    enable_testing()
    find_package(Qt${QT_VERSION_MAJOR} REQUIRED COMPONENTS Test)
    add_library(fakecore SHARED tests/fakecore.cpp)
    set_target_properties(fakecore PROPERTIES OUTPUT_NAME fakecore)

    add_executable(tst_shell tests/tst_shell.cpp qml.qrc)
    target_link_libraries(tst_shell PRIVATE fotozshell Qt${QT_VERSION_MAJOR}::Test Qt${QT_VERSION_MAJOR}::Qml Qt${QT_VERSION_MAJOR}::Quick Qt${QT_VERSION_MAJOR}::QuickControls2)
    target_compile_definitions(tst_shell PRIVATE FAKECORE_PATH="$<TARGET_FILE:fakecore>" FOTOZ_VERSION="${PROJECT_VERSION}")
    add_dependencies(tst_shell fakecore)
    add_test(NAME shell_with_fake_core COMMAND tst_shell)
    set_tests_properties(shell_with_fake_core PROPERTIES ENVIRONMENT "QT_QPA_PLATFORM=offscreen")

    if(FOTOZ_CORE_LIB)
        add_executable(tst_realcore tests/tst_realcore.cpp qml.qrc)
        target_link_libraries(tst_realcore PRIVATE fotozshell Qt${QT_VERSION_MAJOR}::Test Qt${QT_VERSION_MAJOR}::Qml Qt${QT_VERSION_MAJOR}::Quick Qt${QT_VERSION_MAJOR}::QuickControls2)
        target_compile_definitions(tst_realcore PRIVATE REALCORE_PATH="${FOTOZ_CORE_LIB}" FOTOZ_VERSION="${PROJECT_VERSION}")
        add_test(NAME shell_with_real_core COMMAND tst_realcore)
        set_tests_properties(shell_with_real_core PROPERTIES ENVIRONMENT "QT_QPA_PLATFORM=offscreen")
    endif()
endif()
