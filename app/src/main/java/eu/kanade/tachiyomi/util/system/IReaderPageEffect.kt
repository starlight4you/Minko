package eu.kanade.tachiyomi.util.system

import android.app.Activity
import android.content.Context
import android.view.Surface
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Bridge to the iReader (掌阅) SmartOS vendor EPDC interface that arms the firmware
 * water-ripple page-turn animation for the next frame posted to the display controller.
 *
 * Protocol (reverse engineered by the KOReader ireader.koplugin project, legadoM-Ink
 * and Shihon):
 *
 *     EPDCDevice.nativePostCommand("next-effect-type <effect>")
 *     EPDCDevice.setForceNextPostMode(0x01000063)
 *
 * where effect = direction code | speed flag. Direction codes depend on the current
 * Surface rotation; speed flags are 128 (slow), 64 (standard), 0 (fast).
 *
 * The vendor class is located reflectively: the known exact signatures are tried
 * first, then methods are discovered by shape (name hint + parameter types), because
 * firmware variants differ. Both static and instance methods are supported (an
 * instance is obtained from a static no-argument singleton getter).
 *
 * On non-iReader devices the class simply doesn't exist and every call is a no-op.
 */
object IReaderPageEffect {

    private const val EPDC_CLASS_NAME = "android.eink.EPDCDevice"
    private const val FORCE_NEXT_POST_MODE_PAGE_TURN = 0x01000063

    // Direction codes indexed by Surface rotation (0..3). Landscape entries are
    // swapped relative to the community reference table, as verified on device
    // by the ireader.koplugin project.
    private val DIRECTIONS_FORWARD = intArrayOf(1, 3, 2, 4)
    private val DIRECTIONS_BACKWARD = intArrayOf(2, 4, 1, 3)

    private var probed = false
    private var available = false

    private var postMethod: Method? = null
    private var forceMethod: Method? = null
    private var epdcInstance: Any? = null

    val isAvailable: Boolean
        get() = probe()

    @Synchronized
    private fun probe(): Boolean {
        if (probed) return available
        probed = true

        val clazz = try {
            Class.forName(EPDC_CLASS_NAME)
        } catch (e: ClassNotFoundException) {
            logcat(LogPriority.WARN) { "iReader EPDC: $EPDC_CLASS_NAME not found, water-ripple page turn unavailable" }
            return false
        }

        try {
            val methods = clazz.methods

            fun instance(): Any? {
                if (epdcInstance == null) {
                    epdcInstance = methods
                        .filter {
                            Modifier.isStatic(it.modifiers) &&
                                it.parameterCount == 0 &&
                                it.returnType == clazz
                        }
                        .sortedBy {
                            val name = it.name.lowercase()
                            when {
                                name.contains("getinstance") -> 0
                                name.contains("instance") -> 1
                                name.contains("get") -> 2
                                else -> 3
                            }
                        }
                        .firstNotNullOfOrNull { method ->
                            runCatching { method.invoke(null) }.getOrNull()
                        }
                }
                return epdcInstance
            }

            fun bind(method: Method): Method? = when {
                Modifier.isStatic(method.modifiers) -> method
                instance() != null -> method
                else -> null
            }

            postMethod = runCatching {
                clazz.getMethod("nativePostCommand", String::class.java)
            }.getOrNull()?.let(::bind)
                ?: methods
                    .filter {
                        val name = it.name.lowercase()
                        (name.contains("postcommand") || name.contains("post")) &&
                            it.parameterTypes.contentEquals(arrayOf(String::class.java))
                    }
                    .sortedByDescending { Modifier.isStatic(it.modifiers) }
                    .firstNotNullOfOrNull(::bind)

            forceMethod = runCatching {
                clazz.getMethod("setForceNextPostMode", Int::class.java)
            }.getOrNull()?.let(::bind)
                ?: methods
                    .filter {
                        it.name.lowercase().contains("force") &&
                            it.parameterTypes.contentEquals(arrayOf(Int::class.java))
                    }
                    .sortedByDescending { Modifier.isStatic(it.modifiers) }
                    .firstNotNullOfOrNull(::bind)

            if (postMethod == null) {
                logcat(LogPriority.WARN) {
                    "iReader EPDC: no post-command method found; class declares: " +
                        methods.take(16).joinToString { "${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }})" }
                }
                return false
            }

            available = true
            logcat(LogPriority.INFO) { "iReader EPDC: ready via ${postMethod?.name}, force mode via ${forceMethod?.name}" }
        } catch (t: Throwable) {
            logcat(LogPriority.WARN) { "iReader EPDC: probe failed: ${t.message}" }
        }
        return available
    }

    /**
     * Arms the water-ripple animation for the next posted frame. Must be called right
     * before the page turn is committed so the effect lands on that frame.
     *
     * @param forward true for a turn to the next page, false for the previous page
     * @param speedFlag 128 = slow, 64 = standard, 0 = fast
     */
    fun onPageTurn(context: Context, forward: Boolean, speedFlag: Int) {
        if (!probe()) return

        @Suppress("DEPRECATION")
        val rotation = (context as? Activity)?.windowManager?.defaultDisplay?.rotation
            ?: Surface.ROTATION_0
        val direction = (if (forward) DIRECTIONS_FORWARD else DIRECTIONS_BACKWARD)[rotation % 4]
        val effect = direction or speedFlag

        runCatching {
            invoke(postMethod!!, "next-effect-type $effect")
            forceMethod?.let { invoke(it, FORCE_NEXT_POST_MODE_PAGE_TURN) }
        }.onSuccess {
            logcat(LogPriority.INFO) { "iReader EPDC: page turn armed, effect=$effect" }
        }.onFailure {
            logcat(LogPriority.WARN) { "iReader EPDC: page turn command failed: ${it.message}" }
        }
    }

    private fun invoke(method: Method, arg: Any) {
        if (Modifier.isStatic(method.modifiers)) {
            method.invoke(null, arg)
        } else {
            method.invoke(epdcInstance, arg)
        }
    }
}
