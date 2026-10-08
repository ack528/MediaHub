plugins {
    id("com.android.application") version "9.4.1" apply false
    // 只为把 Kotlin 编译器版本固定成和主工程一致(2.4.20,已在本地缓存里)
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20" apply false
}
