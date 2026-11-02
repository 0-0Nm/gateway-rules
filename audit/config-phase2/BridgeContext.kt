package android.content
import java.io.File
class FakePrefs {
    val values = mutableMapOf<String, Any>()
    var writable = true
    fun getString(key: String, default: String?): String? = synchronized(values) { values[key] as? String ?: default }
    fun getBoolean(key:String,default:Boolean):Boolean=values[key] as? Boolean ?: default
    fun getInt(key:String,default:Int):Int=values[key] as? Int ?: default
    fun edit(): Editor = Editor(this)
    class Editor(private val p: FakePrefs) {
        private val pending = mutableMapOf<String, Any>()
        fun putString(key: String, value: String): Editor { pending[key] = value; return this }
        fun putBoolean(key:String,value:Boolean):Editor{pending[key]=value;return this}
        fun putInt(key:String,value:Int):Editor{pending[key]=value;return this}
        fun apply(){commit()}
        fun commit(): Boolean {
            if (!p.writable) return false
            synchronized(p.values) { p.values.putAll(pending) }; return true
        }
    }
}
open class Context(val filesDir: File) {
    companion object { const val MODE_PRIVATE = 0 }
    val applicationContext: Context get() = this
    fun getExternalFilesDir(type:String?):File?=filesDir
    private val prefs = mutableMapOf<String, FakePrefs>()
    fun getSharedPreferences(name: String, mode: Int): FakePrefs = prefs.getOrPut(name) { FakePrefs() }
}
