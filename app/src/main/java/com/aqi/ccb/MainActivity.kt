package com.aqi.ccb

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            text = "GatewayRules bootstrap OK\nphase=build-pipeline\nrules schema v1"
            textSize = 16f
            setPadding(48, 96, 48, 48)
        }
        setContentView(ScrollView(this).apply { addView(tv) })
    }
}
