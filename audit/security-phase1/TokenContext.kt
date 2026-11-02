package android.content
import java.io.File
class FakePrefs {
    val values = mutableMapOf<String, String>()
    var writable = true
    fun getString(key: String, default: String?): String? = synchronized(values) { values[key] ?: default }
    fun edit(): Editor = Editor(this)
    class Editor(private val p: FakePrefs) {
        private val pending = mutableMapOf<String, String>()
        fun putString(key: String, value: String): Editor { pending[key] = value; return this }
        fun commit(): Boolean {
            if (!p.writable) return false
            synchronized(p.values) { p.values.putAll(pending) }; return true
        }
    }
}
open class Context(val filesDir: File) {
    companion object { const val MODE_PRIVATE = 0 }
    val applicationContext: Context get() = this
    private val prefs = mutableMapOf<String, FakePrefs>()
    fun getSharedPreferences(name: String, mode: Int): FakePrefs = prefs.getOrPut(name) { FakePrefs() }
}
