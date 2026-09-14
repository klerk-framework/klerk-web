package dev.klerkframework.web

import dev.klerkframework.klerk.datatypes.EnumContainer
import dev.klerkframework.klerk.misc.ObjectSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ValueWithCorrectTypeTest {

    class Holder {
        enum class Size { Small, Large }
    }

    class SizeContainer(value: Holder.Size) : EnumContainer<Holder.Size>(value)

    data class Params(val size: SizeContainer, val optionalSize: SizeContainer?)

    private val schema = ObjectSchema.of(Params::class)

    @Test
    fun `An enum declared inside a class is parsed`() {
        val value = valueWithCorrectType("Large", schema.field("size")!!) as SizeContainer
        assertEquals(Holder.Size.Large, value.value)
    }

    @Test
    fun `No selection of a nullable enum is null`() {
        assertNull(valueWithCorrectType("", schema.field("optionalSize")!!))
    }
}
