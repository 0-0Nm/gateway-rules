package com.aqi.ccb.gateway
// JVM adapter only: real Android service dispatch is separately verified by Android build/device acceptance.
object GatewayService {
    @Volatile var current:GatewayServer?=null
    fun start(ctx:android.content.Context,port:Int){ throw IllegalStateException("Android service dispatch not available in JVM fixture") }
    fun stop(ctx:android.content.Context){ current?.stop();current=null }
}
