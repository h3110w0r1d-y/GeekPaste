package com.h3110w0r1d.geekpaste

import android.os.Build
import androidx.annotation.Keep
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers.findAndHookMethod
import de.robv.android.xposed.XposedHelpers.findClass
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

@Keep
class Hook : IXposedHookLoadPackage {
    companion object {
        const val PACKAGE_NAME = BuildConfig.APPLICATION_ID
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName == PACKAGE_NAME) {
            hookSelf(lpparam)
        } else if (lpparam.packageName == "android") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                hookCS34(lpparam)
            } else {
                hookCS(lpparam)
            }
        }
    }

    private fun hookSelf(lpparam: LoadPackageParam) {
        val clazz =
            findClass(
                "${PACKAGE_NAME}.utils.XposedUtil",
                lpparam.classLoader,
            )
        findAndHookMethod(
            clazz,
            "isModuleEnabled",
            object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun beforeHookedMethod(param: MethodHookParam?) {
                    super.beforeHookedMethod(param)
                    param?.result = true
                }
            },
        )
        findAndHookMethod(
            clazz,
            "getModuleVersion",
            object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun beforeHookedMethod(param: MethodHookParam?) {
                    super.beforeHookedMethod(param)
                    param?.result = XposedBridge.getXposedVersion()
                }
            },
        )
    }

    private fun hookCS34(lpparam: LoadPackageParam) {
        val clazz =
            findClass(
                "com.android.server.clipboard.ClipboardService",
                lpparam.classLoader,
            )
        findAndHookMethod(
            clazz,
            "clipboardAccessAllowed",
            Int::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun afterHookedMethod(param: MethodHookParam) {
                    super.afterHookedMethod(param)
                    if (PACKAGE_NAME == param.args[1]) {
                        param.result = true
                    }
                }
            },
        )
    }

    private fun hookCS(lpparam: LoadPackageParam) {
        val clazz =
            findClass(
                "com.android.server.clipboard.ClipboardService",
                lpparam.classLoader,
            )
        findAndHookMethod(
            clazz,
            "clipboardAccessAllowed",
            Int::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun afterHookedMethod(param: MethodHookParam) {
                    super.afterHookedMethod(param)
                    if (PACKAGE_NAME == param.args[1]) {
                        param.result = true
                    }
                }
            },
        )
    }
}

inline fun <reified T> Any.get(field: String): T? =
    try {
        val clazz = this.javaClass
        val declaredField = clazz.getDeclaredField(field)
        declaredField.isAccessible = true
        declaredField.get(this) as T
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
