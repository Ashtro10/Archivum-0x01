package ai.clu.core.settings

import ai.clu.core.config.Settings
import ai.clu.core.config.SimpleSettings
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SimpleSettingsTest {
    @Test fun everySimpleSettingIsARealTestedSetting() {
        for (s in SimpleSettings.all) for (k in s.keys) {
            val d = assertNotNull(Settings[k], "unknown setting $k")
            assertTrue(d.verifiedBy.isNotBlank(), "$k has no verifying test")
            assertTrue(!d.advanced, "$k is an advanced setting")
        }
        assertTrue(SimpleSettings.all.sumOf { it.keys.size } < 30, "the normal screen stays small")
    }
}
