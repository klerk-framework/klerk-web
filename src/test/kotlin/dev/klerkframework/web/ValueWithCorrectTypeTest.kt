package dev.klerkframework.web

import dev.klerkframework.klerk.datatypes.DecimalContainer
import dev.klerkframework.klerk.datatypes.EnumContainer
import dev.klerkframework.klerk.misc.ObjectSchema
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ValueWithCorrectTypeTest {

    class Holder {
        enum class Size { Small, Large }
    }

    class SizeContainer(value: Holder.Size) : EnumContainer<Holder.Size>(value)

    class Amount(value: BigDecimal) : DecimalContainer(value) {
        override val min: BigDecimal = BigDecimal.ZERO
        override val max: BigDecimal = BigDecimal("1000")
        override val scale: Int = 2
    }

    data class Params(val size: SizeContainer, val optionalSize: SizeContainer?, val amount: Amount)

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

    @Test
    fun `A decimal is parsed exactly`() {
        val value = valueWithCorrectType("0.10", schema.field("amount")!!) as Amount
        assertEquals("0.10", value.value.toPlainString())
    }
}
