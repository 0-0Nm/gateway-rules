package com.aqi.ccb.ui
import android.content.Context
import org.json.JSONObject
class UiStyle {
    fun toJson()=JSONObject()
    companion object {
        fun load(context:Context)=UiStyle()
        fun patch(context:Context,json:JSONObject)=UiStyle()
    }
}
