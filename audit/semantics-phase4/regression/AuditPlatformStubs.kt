package com.aqi.ccb.gateway
// Android-only diagnostic adapters, excluded from lifecycle assertions.
object WakeLockHolder { fun refCountPublic()=0; fun isHeldPublic()=false }
object FloatingBall { fun watchdogHoldersPublic(): List<String> = emptyList() }
