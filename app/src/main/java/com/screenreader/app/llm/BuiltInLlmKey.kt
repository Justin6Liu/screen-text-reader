package com.screenreader.app.llm

internal object BuiltInLlmKey {
    // This only hides the key from casual UI inspection. It is not secure against APK reverse engineering.
    val deepSeekApiKey: String
        get() = arrayOf(
            "sk-",
            "3976ca27",
            "cfc6450a",
            "aa3977ee",
            "62056efd"
        ).joinToString(separator = "")
}
