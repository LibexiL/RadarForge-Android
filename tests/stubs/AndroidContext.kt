// Minimal stand-ins for the Android classes DataManager uses, so it runs on the JVM in tests.
package android.content
open class Context(val cacheDir: java.io.File)
