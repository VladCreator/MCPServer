package com.vlad.mcpserver

class Greeting {
    private val platform = getPlatform()

    fun greet(): String {
        return sayHello(platform.name)
    }
}