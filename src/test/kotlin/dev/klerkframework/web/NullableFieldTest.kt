package dev.klerkframework.web

import dev.klerkframework.web.config.*
import io.ktor.http.*
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NullableFieldTest {

    @Test
    fun `null-toggle present causes field to be parsed as null`() {
        val callParams = Parameters.build {
            append("nullableFirstName", "Jane")
        }

        @Suppress("UNCHECKED_CAST")
        val result = createParamClassFromCallParameters(ParamsWithNullable::class, callParams) as ParamsWithNullable
        assertNull(result.nullableFirstName, "nullableFirstName should be null when null-toggle is absent")
    }

    @Test
    fun `without null-toggle nullable field is parsed normally`() {
        val callParams = Parameters.build {
            append("nullableFirstName", "Jane")
            append("null-toggle-nullableFirstName", "on")
        }

        @Suppress("UNCHECKED_CAST")
        val result = createParamClassFromCallParameters(ParamsWithNullable::class, callParams) as ParamsWithNullable
        assertNotNull(result.nullableFirstName, "nullableFirstName should not be null when null-toggle is 'on'")
        kotlin.test.assertEquals("Jane", result.nullableFirstName.value)
    }
}

data class ParamsWithNullable(val nullableFirstName: FirstName?)
