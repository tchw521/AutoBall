// AutoBall 根构建脚本
// 约束：零第三方运行时依赖。仅当 -PuseRhino=true 时引入 Rhino（纯 Java，用于无 NDK 的 CI 逃生口）。
plugins {
    id("com.android.application") version "8.1.4" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.buildDir)
}
