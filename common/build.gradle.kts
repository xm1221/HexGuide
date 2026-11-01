plugins {
    id("hexguide.minecraft")
}

architectury {
    common("fabric", "forge")
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(kotlin("reflect"))

    // We depend on fabric loader here to use the fabric @Environment annotations and get the mixin dependencies
    // Do NOT use other classes from fabric loader
    modImplementation(libs.fabric.loader)
    modApi(libs.architectury)

    modApi(libs.hexcasting.common)

    modApi(libs.clothConfig.common)

    modApi(libs.patchouli.xplat)

    modImplementation(libs.inline.fabric)

    libs.mixinExtras.common.also {
        implementation(it)
        annotationProcessor(it)
    }

    // 纯 JVM 单元测试（codec 等不依赖 Minecraft 的代码）
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
