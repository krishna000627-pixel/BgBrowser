package com.bgbrowser

const val K_NORMAL = 0
const val K_INCOG = 1
const val K_TOR = 2

class MainActivity : BrowserActivity() {
    override val kind: Int get() = K_NORMAL
}

class IncognitoActivity : BrowserActivity() {
    override val kind: Int get() = K_INCOG
    override val serviceClass: Class<out KeepAliveService> get() = IncognitoKeepService::class.java
}

class TorActivity : BrowserActivity() {
    override val kind: Int get() = K_TOR
    override val serviceClass: Class<out KeepAliveService> get() = TorKeepService::class.java
}
