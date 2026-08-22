package dev.klerkframework.web

import dev.klerkframework.klerk.ModelID
import dev.klerkframework.web.config.*
import io.ktor.http.*
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertEquals("Jane", result.nullableFirstName.value)
    }

    @Test
    fun `nullable reference select is parsed even without a null-toggle`() {
        // renderReferenceSelect renders a plain <select> with a "(none)" option, not a null-toggle checkbox -- so a
        // submitted value must not be discarded just because "null-toggle-favourite" is absent (regression test for
        // the bug where every nullable reference/enum select was silently forced to null on submit).
        val callParams = Parameters.build {
            append("favourite", "7")
        }

        @Suppress("UNCHECKED_CAST")
        val result = createParamClassFromCallParameters(ParamsWithNullableReference::class, callParams) as ParamsWithNullableReference
        assertEquals(ModelID<Author>(7), result.favourite, "the selected reference should survive parsing")
    }

    @Test
    fun `nullable reference select with the none option is parsed as null`() {
        val callParams = Parameters.build {
            append("favourite", "")
        }

        @Suppress("UNCHECKED_CAST")
        val result = createParamClassFromCallParameters(ParamsWithNullableReference::class, callParams) as ParamsWithNullableReference
        assertNull(result.favourite, "the '(none)' option should be parsed as null")
    }
}

data class ParamsWithNullable(val nullableFirstName: FirstName?)
data class ParamsWithNullableReference(val favourite: ModelID<Author>?)
