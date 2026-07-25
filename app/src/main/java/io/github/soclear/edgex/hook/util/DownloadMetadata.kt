package io.github.soclear.edgex.hook.util

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

data class DownloadMetadata(
    val url: String,
    val mimeType: String?,
    val cookie: String?,
    val userAgent: String?,
    val referrer: String?,
    val totalBytes: Long,
)

/**
 * Edge obfuscates Chromium model fields on every major update. API 150 changed the short field
 * names used by EdgeX 2.2.0, so resolve values structurally and only retain the old names as a
 * fast-path for older versions.
 */
object DownloadMetadataExtractor {
    fun extract(downloadItem: Any): DownloadMetadata? {
        val info = readNamedField(downloadItem, "c")
            ?.takeIf(::looksLikeDownloadInfo)
            ?: allFields(downloadItem.javaClass)
                .firstOrNull { looksLikeDownloadInfoType(it.type) }
                ?.read(downloadItem)
            ?: return null

        val urlValues = urlValues(info)
        val url = namedUrl(info, "a")
            ?: urlValues.firstOrNull(::isHttpUrl)
            ?: return null
        val referrer = namedUrl(info, "h")
            ?: urlValues.firstOrNull { it != url && isHttpUrl(it) }

        val strings = stringValues(info)
        val mimeType = namedString(info, "c")?.takeIf(::looksLikeMimeType)
            ?: strings.firstOrNull(::looksLikeMimeType)
        val userAgent = namedString(info, "b")?.takeIf(::looksLikeUserAgent)
            ?: strings.firstOrNull(::looksLikeUserAgent)
        val cookie = namedString(info, "d")?.takeIf { looksLikeCookie(it, mimeType, userAgent) }
            ?: strings.firstOrNull { looksLikeCookie(it, mimeType, userAgent) }

        return DownloadMetadata(
            url = url,
            mimeType = mimeType,
            cookie = cookie,
            userAgent = userAgent,
            referrer = referrer,
            totalBytes = namedLong(info, "k") ?: inferTotalBytes(info),
        )
    }

    private fun looksLikeDownloadInfo(value: Any): Boolean =
        looksLikeDownloadInfoType(value.javaClass)

    private fun looksLikeDownloadInfoType(type: Class<*>): Boolean =
        type.name.endsWith(".DownloadInfo") || type.simpleName == "DownloadInfo"

    private fun urlValues(info: Any): List<String> {
        val values = linkedSetOf<String>()
        allFields(info.javaClass)
            .filter { field ->
                val name = field.type.name
                name.endsWith(".GURL") || name.endsWith(".Url") ||
                    field.type.simpleName == "GURL"
            }
            .mapNotNullTo(values) { field -> field.read(info)?.let(::urlString) }
        noArgMethods(info.javaClass)
            .filter { method ->
                val name = method.returnType.name
                name.endsWith(".GURL") || name.endsWith(".Url") ||
                    method.returnType.simpleName == "GURL"
            }
            .mapNotNullTo(values) { method -> method.call(info)?.let(::urlString) }
        return values.toList()
    }

    private fun stringValues(info: Any): List<String> {
        val values = linkedSetOf<String>()
        allFields(info.javaClass)
            .filter { it.type == String::class.java }
            .mapNotNullTo(values) { it.read(info) as? String }
        noArgMethods(info.javaClass)
            .filter { it.returnType == String::class.java }
            .mapNotNullTo(values) { it.call(info) as? String }
        return values.filter(String::isNotBlank)
    }

    private fun urlString(value: Any): String? {
        if (value is String) return value.takeIf(String::isNotBlank)
        val candidates = noArgMethods(value.javaClass)
            .filter { it.returnType == String::class.java }
            .sortedBy { method ->
                when (method.name) {
                    "getSpec", "getPossiblyInvalidSpec", "j" -> 0
                    "toString" -> 2
                    else -> 1
                }
            }
        return candidates.asSequence()
            .mapNotNull { it.call(value) as? String }
            .firstOrNull(::isHttpUrl)
            ?: value.toString().takeIf(::isHttpUrl)
    }

    private fun namedUrl(info: Any, name: String): String? =
        readNamedField(info, name)?.let(::urlString)?.takeIf(::isHttpUrl)

    private fun namedString(info: Any, name: String): String? =
        readNamedField(info, name) as? String

    private fun namedLong(info: Any, name: String): Long? =
        (readNamedField(info, name) as? Number)?.toLong()?.takeIf { it >= 0 }

    private fun inferTotalBytes(info: Any): Long {
        val values = buildList {
            allFields(info.javaClass)
                .filter { it.type == Long::class.javaPrimitiveType || it.type == Long::class.java }
                .mapNotNullTo(this) { (it.read(info) as? Number)?.toLong() }
            noArgMethods(info.javaClass)
                .filter { it.returnType == Long::class.javaPrimitiveType || it.returnType == Long::class.java }
                .mapNotNullTo(this) { (it.call(info) as? Number)?.toLong() }
        }
        // Epoch timestamps and native pointers are much larger than realistic download sizes.
        return values.filter { it in 0..999_999_999_999L }.maxOrNull() ?: -1L
    }

    private fun looksLikeMimeType(value: String): Boolean =
        value.length in 3..255 &&
            value.count { it == '/' } == 1 &&
            !value.contains(' ') &&
            !value.startsWith("http", ignoreCase = true)

    private fun looksLikeUserAgent(value: String): Boolean =
        value.contains("Mozilla/", ignoreCase = true) ||
            value.contains(" EdgA/", ignoreCase = true) ||
            value.contains(" Chrome/", ignoreCase = true)

    private fun looksLikeCookie(value: String, mimeType: String?, userAgent: String?): Boolean =
        value != mimeType &&
            value != userAgent &&
            !isHttpUrl(value) &&
            (value.contains('=') || value.contains(';'))

    private fun isHttpUrl(value: String): Boolean =
        value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)

    private fun readNamedField(instance: Any, name: String): Any? =
        allFields(instance.javaClass).firstOrNull { it.name == name }?.read(instance)

    private fun allFields(type: Class<*>): Sequence<Field> =
        generateSequence(type as Class<*>?) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filterNot { Modifier.isStatic(it.modifiers) }

    private fun noArgMethods(type: Class<*>): Sequence<Method> =
        generateSequence(type as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .filter { !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 }

    private fun Field.read(instance: Any): Any? = runCatching {
        isAccessible = true
        get(instance)
    }.getOrNull()

    private fun Method.call(instance: Any): Any? = runCatching {
        isAccessible = true
        invoke(instance)
    }.getOrNull()
}
