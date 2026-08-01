package com.mist3s.nopecall.core

import com.mist3s.nopecall.core.sim.SimLabels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Короткая форма метки SIM (ТЗ §7.4).
 *
 * В фильтре журнала стоял `phoneAccountId` — на большинстве прошивок это серийный номер карты
 * вида `89701201869002096644`. Выбрать по нему нужную SIM невозможно, а придумывать «SIM 1»
 * без данных о слоте нельзя: порядок появления в журнале со слотами не связан.
 */
class SimLabelsTest {

    @Test
    fun `длинный идентификатор сокращается до последних цифр`() {
        assertEquals("Карта …6644", SimLabels.shortLabel("89701201869002096644"))
        assertEquals("Карта …737F", SimLabels.shortLabel("8970162100080595737F"))
    }

    @Test
    fun `короткий идентификатор показывается целиком`() {
        assertEquals("Карта 12", SimLabels.shortLabel("12"))
        assertEquals("Карта 1234", SimLabels.shortLabel("1234"))
    }

    @Test
    fun `пустой идентификатор не превращается в пустую метку`() {
        // Пустая строка в выпадающем списке — это невыбираемый пункт.
        assertEquals("Карта без метки", SimLabels.shortLabel(""))
        assertEquals("Карта без метки", SimLabels.shortLabel("   "))
    }

    @Test
    fun `резервный источник не выдаёт короткую форму за имя`() {
        // Интерфейс по этому признаку решает, объяснять ли, какого разрешения не хватает.
        // Признак у каждой карты свой: разрешение может быть выдано, а карта — уже вынута,
        // и тогда общий флаг убрал бы пояснение, оставив короткую форму без объяснения.
        val label = SimLabels.FALLBACK.labelFor("89701201869002096644")
        assertEquals("Карта …6644", label.text)
        assertFalse(label.known)
    }
}
