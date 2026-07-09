cmake_minimum_required(VERSION 3.22.1)
project("localaudiototext")

set(CMAKE_CXX_STANDARD 11)

# ════════════════════════════════════════════════════════════
# ── Whisper.cpp (existing STT backend) ──
# ════════════════════════════════════════════════════════════

# Disable tests and examples from whisper.cpp
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "")
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "")
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "")

add_subdirectory(whisper)

add_library(
        whisper_jni
        SHARED
        whisper_jni.cpp
)

find_library(
        log-lib
        log
)

target_link_libraries(
        whisper_jni
        whisper
        ${log-lib}
)

# ════════════════════════════════════════════════════════════
# ── espeak-ng + KittenTTS phonemizer (new TTS backend) ──
# ════════════════════════════════════════════════════════════

# espeak-ng source directory (cloned into cpp/espeak-ng/)
set(ESPEAK_SRC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/espeak-ng)

# Only build KittenTTS native if espeak-ng source is present
if(EXISTS ${ESPEAK_SRC_DIR}/src/libespeak-ng/speech.c)

    # Disable features we don't need
    set(USE_ASYNC OFF CACHE BOOL "" FORCE)
    set(USE_MBROLA OFF CACHE BOOL "" FORCE)

    # Generate config.h for espeak-ng
    file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/espeak_config.h
    "#define HAVE_MKSTEMP 1\n\
#define HAVE_NBTOOL_CONFIG_H 0\n\
#define HAVE_DECL_OPTRESET 0\n\
#define HAVE_SYS_ENDIAN_H 0\n\
#define HAVE_ISWBLANK 1\n\
\n\
#define USE_ASYNC 0\n\
#define USE_KLATT 0\n\
#define USE_LIBPCAUDIO 0\n\
#define USE_LIBSONIC 0\n\
#define USE_MBROLA 0\n\
#define USE_SPEECHPLAYER 0\n\
\n\
#define PACKAGE_VERSION \"1.52.0.1\"\n"
    )

    # Rename to config.h in the binary dir
    file(RENAME ${CMAKE_CURRENT_BINARY_DIR}/espeak_config.h ${CMAKE_CURRENT_BINARY_DIR}/config.h)

    # espeak-ng core source files needed for phonemization
    set(ESPEAK_SOURCES
        ${ESPEAK_SRC_DIR}/src/libespeak-ng/common.c
        ${ESPEAK_SRC_DIR}/src/libespeak-ng/compiledict.c
        ${ESPEAK_SRC_DIR}/src/libespeak-ng/dictionary.c
        ${ESPEAK_SRC_DIR}/src/libespeak-ng/encoding.c
        ${ESPEAK_SRC_DIR}/src/libespeak-ng/error.c
        ${ESPEAK_SRC_DIR}/src/libespeak-ng/espeak_api.c
        ${ESPEAK_SRC_DIR}/src/libespeak-ng/ieee80.c
        ${ESPEAK_SRC_DIR}/src/libespeak-ng/intonation.c
        ${ESPEAK_SRC_DIR}/src/libespeak-ng/langopts.c
        ${ESPEAK_SRC_DIR}/src/libespeak-ng/mnemonics.c
        ${ESPEAK_SRC_DIR}/src/libespeak-ng/numbers.c
        ${ESPEAK_SRC_DIR}/src/libespeak-ng/phoneme.c
        ${ESPEAK_SRC_DIR}/src/libespeak-ng/phonemelist.c
        ${ESPEAK_SRC_DIR}/src/libespeak-ng/readclause.c
        ${ESPEAK_SRC_DIR}/src/libespeak-ng/setlengths.c
        ${ESPEAK_SRC_DIR}/src/libespeak-ng/soundicon.c
        ${ESPEAK_SRC_DIR}/src/libespeak-ng/spect.c
        ${ESPEAK_SRC_DIR}/src/libespeak-ng/speech.c
        ${ESPEAK_SRC_DIR}/src/libespeak-ng/ssml.c
        ${ESPEAK_SRC_DIR}/src/libespeak-ng/synthdata.c
        ${ESPEAK_SRC_DIR}/src/libespeak-ng/synthesize.c
        ${ESPEAK_SRC_DIR}/src/libespeak-ng/synth_mbrola.c
        ${ESPEAK_SRC_DIR}/src/libespeak-ng/translate.c
        ${ESPEAK_SRC_DIR}/src/libespeak-ng/translateword.c
        ${ESPEAK_SRC_DIR}/src/libespeak-ng/tr_languages.c
        ${ESPEAK_SRC_DIR}/src/libespeak-ng/voices.c
        ${ESPEAK_SRC_DIR}/src/libespeak-ng/wavegen.c
        ${ESPEAK_SRC_DIR}/src/libespeak-ng/compilembrola.c
    )

    # Check if the ucd source files exist (newer espeak-ng versions)
    if(EXISTS ${ESPEAK_SRC_DIR}/src/ucd-tools/src/case.c)
        list(APPEND ESPEAK_SOURCES
            ${ESPEAK_SRC_DIR}/src/ucd-tools/src/case.c
            ${ESPEAK_SRC_DIR}/src/ucd-tools/src/categories.c
            ${ESPEAK_SRC_DIR}/src/ucd-tools/src/ctype.c
            ${ESPEAK_SRC_DIR}/src/ucd-tools/src/proplist.c
            ${ESPEAK_SRC_DIR}/src/ucd-tools/src/scripts.c
            ${ESPEAK_SRC_DIR}/src/ucd-tools/src/tostring.c
        )
    endif()

    # Build espeak-ng as a static library
    add_library(espeak-ng STATIC ${ESPEAK_SOURCES})

    target_include_directories(espeak-ng PRIVATE
        ${CMAKE_CURRENT_BINARY_DIR}
        ${ESPEAK_SRC_DIR}/src/include
        ${ESPEAK_SRC_DIR}/src/libespeak-ng
        ${ESPEAK_SRC_DIR}/src/ucd-tools/src/include
    )

    target_compile_definitions(espeak-ng PRIVATE
        LIBESPEAK_NG_EXPORT=__attribute__\(\(visibility\("default"\)\)\)
        DATA_FROM_SOURCEDIR_PATH=NULL
    )

    # Build JNI bridge shared library
    add_library(kittentts-native SHARED
        phonemize_jni.cpp
    )

    target_include_directories(kittentts-native PRIVATE
        ${ESPEAK_SRC_DIR}/src/include
    )

    target_link_libraries(kittentts-native
        espeak-ng
        ${log-lib}
    )

endif()

