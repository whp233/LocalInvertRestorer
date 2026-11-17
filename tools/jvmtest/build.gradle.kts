plugins {
    kotlin("jvm") version "1.9.24"
    application
}

repositories { mavenCentral() }

sourceSets {
    main {
        kotlin.srcDir("src/main/kotlin")
        // 直接复用 App 的核心算法源码，保证验证的就是上机跑的那份代码
        kotlin.srcDir("../../app/src/main/java/com/invrestore/core")
    }
}

kotlin { jvmToolchain(20) }

application { mainClass.set("harness.MainKt") }
