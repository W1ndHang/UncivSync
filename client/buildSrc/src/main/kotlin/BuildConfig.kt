@file:Suppress("InvalidPackageDeclaration")
package com.unciv.build

object BuildConfig {
    const val appName = "UncivSync"
    const val appCodeNumber = 1260
    // UncivSync 发行版本号：1.0.00 起，最后一位十进制递增（1.0.01 / 1.0.02 / ...）。
    // 每次构建发布前手动 +1。appVersion 上游引擎版本见 Versioning.kt，不在此覆盖。
    const val releaseVersion = "1.0.02"
    const val appVersion = "4.21.19-1.0.02"
    const val identifier = "com.unciv.app"
}
