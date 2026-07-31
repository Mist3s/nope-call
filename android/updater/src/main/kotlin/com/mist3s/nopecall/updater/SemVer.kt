package com.mist3s.nopecall.updater

/**
 * Версия по правилам semver с предвыпусками (ТЗ §15.2, §15.5).
 *
 * Сравнение версий — **чистая функция**, и это сделано намеренно: именно она решает, предлагать
 * обновление или нет, а проверить решение на устройстве дорого и медленно. Здесь нет ни сети,
 * ни Android SDK, поэтому все правила покрыты тестами на голой JVM.
 *
 * Почему сравнивается `versionName`, а не `versionCode`: при `--split-per-abi` Flutter добавляет
 * к `versionCode` смещение по ABI (ТЗ §15.2), поэтому число из установленной копии и число `build`
 * из манифеста несопоставимы напрямую. `versionName` от ABI не зависит.
 *
 * Известное свойство semver, о котором стоит помнить при выпуске предвыпусков: буквенно-цифровые
 * идентификаторы сравниваются как строки, то есть `1.2.3-rc10` **младше** `1.2.3-rc2`. Если
 * предвыпусков окажется больше девяти, тег должен выглядеть как `1.2.3-rc.10` — точка делает
 * `10` числовым идентификатором, и порядок становится числовым. Отклонённая альтернатива —
 * «умное» отделение цифрового хвоста от `rc`: это уже не semver, и внешние инструменты
 * (в том числе GitHub) сравнивали бы теги иначе, чем приложение.
 */
public class SemVer private constructor(
    public val major: Int,
    public val minor: Int,
    public val patch: Int,
    public val prerelease: List<String>,
) : Comparable<SemVer> {

    public val isPrerelease: Boolean get() = prerelease.isNotEmpty()

    override fun compareTo(other: SemVer): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        patch.compareTo(other.patch).let { if (it != 0) return it }

        // Релиз старше любого своего предвыпуска: 1.2.3 новее 1.2.3-rc1 (ТЗ §15.5).
        if (prerelease.isEmpty() && other.prerelease.isEmpty()) return 0
        if (prerelease.isEmpty()) return 1
        if (other.prerelease.isEmpty()) return -1

        for (index in 0 until minOf(prerelease.size, other.prerelease.size)) {
            val result = compareIdentifiers(prerelease[index], other.prerelease[index])
            if (result != 0) return result
        }
        // Одинаковое начало — короче значит младше: 1.2.3-rc1 младше 1.2.3-rc1.2.
        return prerelease.size.compareTo(other.prerelease.size)
    }

    override fun equals(other: Any?): Boolean = other is SemVer && compareTo(other) == 0

    override fun hashCode(): Int {
        var result = major
        result = 31 * result + minor
        result = 31 * result + patch
        result = 31 * result + prerelease.hashCode()
        return result
    }

    override fun toString(): String =
        "$major.$minor.$patch" + if (prerelease.isEmpty()) "" else "-" + prerelease.joinToString(".")

    public companion object {

        /**
         * Разбор `1.2.3` или `1.2.3-rc1`; ведущая `v` из имени тега допускается,
         * метаданные сборки после `+` отбрасываются, как того требует semver.
         *
         * @return `null`, если строка не версия. `null`, а не исключение: неразобранная версия —
         *   не сбой, а причина не предлагать обновление, и вызывающий обязан это решить сам.
         */
        public fun parseOrNull(text: String?): SemVer? {
            val trimmed = text?.trim()?.removePrefix("v")?.removePrefix("V")
            if (trimmed.isNullOrEmpty()) return null

            // Метаданные сборки в сравнении не участвуют по стандарту, поэтому режутся первыми.
            val withoutBuild = trimmed.substringBefore('+')
            val dash = withoutBuild.indexOf('-')
            val core = if (dash < 0) withoutBuild else withoutBuild.substring(0, dash)
            val prereleaseText = if (dash < 0) null else withoutBuild.substring(dash + 1)

            val parts = core.split('.')
            if (parts.size != 3) return null
            val numbers = parts.map { part ->
                if (part.isEmpty() || part.any { it !in '0'..'9' }) return null
                part.toIntOrNull() ?: return null
            }

            val prerelease = if (prereleaseText == null) {
                emptyList()
            } else {
                val ids = prereleaseText.split('.')
                if (ids.any { id -> id.isEmpty() || id.any { !isIdentifierChar(it) } }) return null
                ids
            }

            return SemVer(numbers[0], numbers[1], numbers[2], prerelease)
        }

        private fun isIdentifierChar(c: Char): Boolean =
            c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z' || c == '-'

        /**
         * Идентификатор предвыпуска: числовые сравниваются как числа и всегда младше
         * буквенно-цифровых, буквенно-цифровые — как строки ASCII (правила semver 11.4).
         *
         * Числа сравниваются по длине, а затем посимвольно, а не через `toLong()`: идентификатор
         * может быть длиннее 19 цифр, и переполнение молча дало бы неверный порядок версий.
         */
        private fun compareIdentifiers(a: String, b: String): Int {
            val aNumeric = a.all { it in '0'..'9' }
            val bNumeric = b.all { it in '0'..'9' }
            return when {
                aNumeric && bNumeric -> {
                    val x = a.trimStart('0').ifEmpty { "0" }
                    val y = b.trimStart('0').ifEmpty { "0" }
                    if (x.length != y.length) x.length.compareTo(y.length) else x.compareTo(y)
                }
                aNumeric -> -1
                bNumeric -> 1
                else -> a.compareTo(b)
            }
        }
    }
}
