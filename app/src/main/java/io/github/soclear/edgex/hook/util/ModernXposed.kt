package io.github.soclear.edgex.hook.util

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Small compatibility facade used while the module's hooks move from the legacy API to
 * libxposed API 102. This is module-owned code and never calls the legacy Xposed API.
 */
object ModernXposed {
    lateinit var api: XposedInterface
        private set

    fun attach(api: XposedInterface) {
        this.api = api
    }
}

open class XC_MethodHook {
    open fun beforeHookedMethod(param: MethodHookParam) = Unit
    open fun afterHookedMethod(param: MethodHookParam) = Unit

    class MethodHookParam internal constructor(
        val method: Executable,
        val thisObject: Any,
        val args: Array<Any>,
    ) {
        private var resultAssigned = false
        private var throwableAssigned = false

        var result: Any? = null
            set(value) {
                field = value
                resultAssigned = true
                throwable = null
                throwableAssigned = false
            }

        var throwable: Throwable? = null
            set(value) {
                field = value
                throwableAssigned = value != null
                if (value != null) resultAssigned = false
            }

        internal fun shouldReturnEarly(): Boolean = resultAssigned || throwableAssigned

        internal fun setOutcome(value: Any?, error: Throwable?) {
            result = value
            resultAssigned = false
            throwable = error
            throwableAssigned = false
        }
    }
}

abstract class XC_MethodReplacement : XC_MethodHook() {
    abstract fun replaceHookedMethod(param: MethodHookParam): Any?
}

object XposedBridge {
    fun hookMethod(method: Executable, callback: XC_MethodHook): XposedInterface.HookHandle =
        hook(method, callback)

    fun hookAllConstructors(
        clazz: Class<*>,
        callback: XC_MethodHook,
    ): Set<XposedInterface.HookHandle> = clazz.declaredConstructors
        .mapTo(linkedSetOf()) { hook(it, callback) }

    fun hookAllMethods(
        clazz: Class<*>,
        methodName: String,
        callback: XC_MethodHook,
    ): Set<XposedInterface.HookHandle> = clazz.declaredMethods
        .filter { it.name == methodName }
        .mapTo(linkedSetOf()) { hook(it, callback) }

    private fun hook(
        executable: Executable,
        callback: XC_MethodHook,
    ): XposedInterface.HookHandle {
        executable.isAccessible = true
        return ModernXposed.api.hook(executable)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val args = chain.args.toTypedArray()
                val param = XC_MethodHook.MethodHookParam(
                    chain.executable,
                    chain.thisObject ?: error("Static hooks are not supported by this facade"),
                    args,
                )

                if (callback is XC_MethodReplacement) {
                    return@intercept callback.replaceHookedMethod(param)
                }

                callback.beforeHookedMethod(param)
                if (param.shouldReturnEarly()) {
                    param.throwable?.let { throw it }
                    return@intercept param.result
                }

                var result: Any? = null
                var error: Throwable? = null
                try {
                    result = chain.proceed(args)
                } catch (t: Throwable) {
                    error = t
                }
                param.setOutcome(result, error)
                callback.afterHookedMethod(param)
                param.throwable?.let { throw it }
                param.result
            }
    }

    fun log(message: String) {
        ModernXposed.api.log(Log.INFO, "EdgeX", message)
    }

    fun log(throwable: Throwable) {
        ModernXposed.api.log(Log.ERROR, "EdgeX", throwable.message ?: "Hook error", throwable)
    }
}

@Suppress("UNCHECKED_CAST")
object XposedHelpers {
    private val additionalInstanceFields =
        Collections.synchronizedMap(WeakHashMap<Any, MutableMap<String, Any?>>())
    private val additionalStaticFields =
        ConcurrentHashMap<Any, ConcurrentHashMap<String, Any?>>()

    fun findClass(name: String, classLoader: ClassLoader?): Class<*> =
        Class.forName(name, false, classLoader)

    fun findClassIfExists(name: String, classLoader: ClassLoader?): Class<*>? =
        runCatching { findClass(name, classLoader) }.getOrNull()

    fun findField(clazz: Class<*>, name: String): Field =
        generateSequence(clazz as Class<*>?) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredField(name) }.getOrNull() }
            .first()
            .also { it.isAccessible = true }

    fun findMethodExact(clazz: Class<*>, name: String, vararg parameterTypes: Any): Method =
        findMethod(clazz, name, parameterTypes.map(::resolveParameterType).toTypedArray())

    fun findConstructorExact(
        className: String,
        classLoader: ClassLoader?,
        vararg parameterTypes: Any?,
    ): Constructor<*> {
        val clazz = findClass(className, classLoader)
        return clazz.getDeclaredConstructor(
            *parameterTypes.map { resolveParameterType(it ?: error("Null parameter type")) }.toTypedArray()
        ).also { it.isAccessible = true }
    }

    fun findAndHookMethod(
        clazz: Class<*>,
        methodName: String,
        vararg parameterTypesAndCallback: Any?,
    ): XposedInterface.HookHandle {
        val callback = parameterTypesAndCallback.lastOrNull() as? XC_MethodHook
            ?: error("Last argument must be XC_MethodHook")
        val parameterTypes = parameterTypesAndCallback.dropLast(1)
            .map { resolveParameterType(it ?: error("Null parameter type"), clazz.classLoader) }
            .toTypedArray()
        return XposedBridge.hookMethod(findMethod(clazz, methodName, parameterTypes), callback)
    }

    fun getObjectField(instance: Any, name: String): Any? = findField(instance.javaClass, name).get(instance)
    fun getIntField(instance: Any, name: String): Int = findField(instance.javaClass, name).getInt(instance)
    fun getLongField(instance: Any, name: String): Long = findField(instance.javaClass, name).getLong(instance)

    fun callMethod(instance: Any, name: String, vararg args: Any?): Any? {
        val (parameterTypes, actualArgs) = explicitTypesOrInfer(args)
        val method = findCompatibleMethod(instance.javaClass, name, parameterTypes, actualArgs, false)
        return ModernXposed.api.getInvoker(method)
            .setType(XposedInterface.Invoker.Type.ORIGIN)
            .invoke(instance, *actualArgs)
    }

    fun callStaticMethod(clazz: Class<*>, name: String, vararg args: Any?): Any? {
        val (parameterTypes, actualArgs) = explicitTypesOrInfer(args)
        val method = findCompatibleMethod(clazz, name, parameterTypes, actualArgs, true)
        return ModernXposed.api.getInvoker(method)
            .setType(XposedInterface.Invoker.Type.ORIGIN)
            .invoke(null, *actualArgs)
    }

    fun getAdditionalInstanceField(instance: Any, key: String): Any? =
        additionalInstanceFields[instance]?.get(key)

    fun setAdditionalInstanceField(instance: Any, key: String, value: Any?): Any? {
        val values = additionalInstanceFields.getOrPut(instance) { ConcurrentHashMap() }
        return if (value == null) values.remove(key) else values.put(key, value)
    }

    fun getAdditionalStaticField(owner: Any, key: String): Any? =
        additionalStaticFields[owner]?.get(key)

    fun setAdditionalStaticField(owner: Any, key: String, value: Any?): Any? {
        val values = additionalStaticFields.computeIfAbsent(owner) { ConcurrentHashMap() }
        return if (value == null) values.remove(key) else values.put(key, value)
    }

    private fun findMethod(clazz: Class<*>, name: String, parameterTypes: Array<Class<*>>): Method =
        generateSequence(clazz as Class<*>?) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredMethod(name, *parameterTypes) }.getOrNull() }
            .first()
            .also { it.isAccessible = true }

    private fun findCompatibleMethod(
        clazz: Class<*>,
        name: String,
        explicitTypes: Array<Class<*>>?,
        args: Array<Any?>,
        staticOnly: Boolean,
    ): Method {
        if (explicitTypes != null) return findMethod(clazz, name, explicitTypes)
        return generateSequence(clazz as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .first {
                it.name == name &&
                    (!staticOnly || Modifier.isStatic(it.modifiers)) &&
                    parametersMatch(it.parameterTypes, args)
            }
            .also { it.isAccessible = true }
    }

    private fun explicitTypesOrInfer(args: Array<out Any?>): Pair<Array<Class<*>>?, Array<Any?>> {
        if (args.firstOrNull() is Array<*> &&
            (args.first() as Array<*>).all { it == null || it is Class<*> }
        ) {
            val types = (args.first() as Array<*>).map {
                it as? Class<*> ?: error("Null explicit parameter type")
            }.toTypedArray()
            return types to args.drop(1).toTypedArray()
        }
        return null to args.copyOf() as Array<Any?>
    }

    private fun parametersMatch(types: Array<Class<*>>, args: Array<Any?>): Boolean =
        types.size == args.size && types.indices.all { index ->
            val argument = args[index] ?: return@all !types[index].isPrimitive
            boxed(types[index]).isAssignableFrom(argument.javaClass)
        }

    private fun boxed(type: Class<*>): Class<*> = when (type) {
        java.lang.Boolean.TYPE -> Boolean::class.javaObjectType
        java.lang.Byte.TYPE -> Byte::class.javaObjectType
        java.lang.Character.TYPE -> Char::class.javaObjectType
        java.lang.Short.TYPE -> Short::class.javaObjectType
        java.lang.Integer.TYPE -> Int::class.javaObjectType
        java.lang.Long.TYPE -> Long::class.javaObjectType
        java.lang.Float.TYPE -> Float::class.javaObjectType
        java.lang.Double.TYPE -> Double::class.javaObjectType
        java.lang.Void.TYPE -> java.lang.Void::class.java
        else -> type
    }

    private fun resolveParameterType(value: Any, classLoader: ClassLoader? = null): Class<*> = when (value) {
        is Class<*> -> value
        is String -> findClass(value, classLoader)
        else -> error("Unsupported parameter type: $value")
    }
}
