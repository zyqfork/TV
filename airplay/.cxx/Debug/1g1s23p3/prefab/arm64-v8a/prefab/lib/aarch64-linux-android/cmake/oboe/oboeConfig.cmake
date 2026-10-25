if(NOT TARGET oboe::oboe)
add_library(oboe::oboe SHARED IMPORTED)
set_target_properties(oboe::oboe PROPERTIES
    IMPORTED_LOCATION "/data/home/zyq/.gradle/caches/9.6.1/transforms/4b2953fd92f1b0cc2fbabb51b335b77a/transformed/oboe-1.10.0/prefab/modules/oboe/libs/android.arm64-v8a/liboboe.so"
    INTERFACE_INCLUDE_DIRECTORIES "/data/home/zyq/.gradle/caches/9.6.1/transforms/4b2953fd92f1b0cc2fbabb51b335b77a/transformed/oboe-1.10.0/prefab/modules/oboe/include"
    INTERFACE_LINK_LIBRARIES ""
)
endif()

