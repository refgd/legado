package io.legado.app.data.entities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleDataTest {

    @Test
    fun variablesRoundTripThroughNeutralRuleData() {
        val ruleData = RuleData()

        assertTrue(ruleData.putVariable("token", "alpha"))
        assertEquals("alpha", ruleData.getVariable("token"))
        val variableJson = ruleData.getVariable()
        assertNotNull(variableJson)
        assertTrue(variableJson!!.contains("\"token\""))
        assertTrue(variableJson.contains("\"alpha\""))

        assertTrue(ruleData.putVariable("token", null))
        assertEquals("", ruleData.getVariable("token"))
        assertEquals(null, ruleData.getVariable())
    }

    @Test
    fun readConfigConverterAcceptsNullJsonString() {
        val converter = Book.Converters()

        assertNull(converter.stringToReadConfig(null))
        assertNull(converter.stringToReadConfig(""))
        assertNull(converter.stringToReadConfig("null"))
    }
}
