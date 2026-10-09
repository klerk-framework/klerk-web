package dev.klerkframework.web

import dev.klerkframework.klerk.AuthorizationException
import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.datatypes.BooleanContainer
import dev.klerkframework.klerk.datatypes.ByteContainer
import dev.klerkframework.klerk.datatypes.DataContainer
import dev.klerkframework.klerk.datatypes.DateContainer
import dev.klerkframework.klerk.datatypes.DoubleContainer
import dev.klerkframework.klerk.datatypes.DurationContainer
import dev.klerkframework.klerk.datatypes.EnumContainer
import dev.klerkframework.klerk.datatypes.FloatContainer
import dev.klerkframework.klerk.datatypes.InstantContainer
import dev.klerkframework.klerk.datatypes.IntContainer
import dev.klerkframework.klerk.datatypes.LongContainer
import dev.klerkframework.klerk.datatypes.ShortContainer
import dev.klerkframework.klerk.datatypes.StringContainer
import dev.klerkframework.klerk.datatypes.UByteContainer
import dev.klerkframework.klerk.datatypes.UIntContainer
import dev.klerkframework.klerk.datatypes.ULongContainer
import dev.klerkframework.klerk.datatypes.UShortContainer
import dev.klerkframework.klerk.misc.ObjectSchema
import dev.klerkframework.klerk.misc.PropertyType
import dev.klerkframework.klerk.misc.SchemaField
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.html.TD
import java.lang.reflect.ParameterizedType
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * One column of a [TableTemplate]. A column is a value, so adding one is `columns + Column(...)` rather than a
 * setting on the table.
 *
 * A column made with [property] or [value] knows its value, so the table can sort and filter on it. One made with the
 * constructor only knows how to render a cell.
 */
public class Column<T : Any> internal constructor(
    /** The text in the `<th>`. */
    public val header: String,
    /** Identifies the column in the query parameters and in [TableSort]. Null for a column that only renders. */
    public val key: String?,
    internal val kind: ColumnKind?,
    internal val nullable: Boolean,
    /** The value to sort and filter on: a plain value, null, or [Unreadable]. */
    internal val value: ((Model<T>) -> Any?)?,
    internal val sortable: Boolean,
    internal val filterable: Boolean,
    /** The cell as text, for the columns klerk-web renders itself. Those cells link to the model. */
    internal val text: ((Model<T>) -> String)?,
    /** Renders the `<td>` contents for one model. */
    public val cell: TD.(Model<T>) -> Unit,
) {
    /** A column that renders [cell] and can be neither sorted nor filtered. */
    public constructor(header: String, cell: TD.(Model<T>) -> Unit) :
        this(header, null, null, false, null, false, false, null, cell)

    internal val isSortable: Boolean get() = sortable && kind?.sortable == true
    internal val isFilterable: Boolean get() = filterable && kind?.filterable == true

    internal fun withKind(kind: ColumnKind): Column<T> =
        Column(header, key, kind, nullable, value, sortable, filterable, text, cell)

    internal fun withKey(key: String): Column<T> =
        Column(header, key, kind, nullable, value, sortable, filterable, text, cell)

    public companion object {
        /** ID, State, Created, Updated and a summary of the properties. */
        public fun <T : Any> defaults(): List<Column<T>> = listOf(id(), state(), created(), updated(), summary())

        /** The model's id. Filterable, but not sortable since ids carry no order. Key `id`. */
        public fun <T : Any> id(): Column<T> = textColumn(
            header = "ID",
            key = "id",
            kind = ColumnKind.Reference,
            nullable = false,
            sortable = false,
            filterable = true,
            value = { it.id.value },
            text = { it.id.toString() },
        )

        /** The model's state. Sorted in the order the states are declared. Key `state`. */
        public fun <T : Any> state(): Column<T> = textColumn(
            header = "State",
            key = "state",
            kind = ColumnKind.State,
            nullable = false,
            sortable = true,
            filterable = true,
            value = { it.state },
            text = { it.state },
        )

        /** When the model was created. Key `created`. */
        public fun <T : Any> created(): Column<T> = textColumn(
            header = "Created",
            key = "created",
            kind = ColumnKind.InstantKind,
            nullable = false,
            sortable = true,
            filterable = true,
            value = { it.createdAt },
            text = { formatDate(it.createdAt) },
        )

        /** When the model was last modified. Key `updated`. */
        public fun <T : Any> updated(): Column<T> = textColumn(
            header = "Updated",
            key = "updated",
            kind = ColumnKind.InstantKind,
            nullable = false,
            sortable = true,
            filterable = true,
            value = { it.lastModifiedAt },
            text = { formatDate(it.lastModifiedAt) },
        )

        private fun <T : Any> summary(): Column<T> = textColumn(
            header = "Properties",
            key = null,
            kind = null,
            nullable = false,
            sortable = false,
            filterable = false,
            value = null,
            text = { model ->
                val text = model.props.toString()
                if (text.length < 100) text else text.take(100).plus("...")
            },
        )

        /**
         * A column showing one property of the model, e.g. `Column.property(Author::age)`. The header is the
         * property name made readable, and the key is the property name.
         *
         * The property's type decides how it can be filtered. A collection, a nested object, a position or attached
         * data is shown but can be neither sorted nor filtered. Set [sortable] or [filterable] to false to turn either
         * off.
         *
         * @throws IllegalArgumentException if [property] is not a constructor property of [T]
         */
        public inline fun <reified T : Any> property(
            property: KProperty1<T, *>,
            header: String? = null,
            key: String? = null,
            sortable: Boolean = true,
            filterable: Boolean = true,
        ): Column<T> = propertyColumn(T::class, property.name, header, key, sortable, filterable)

        /**
         * A column showing a value computed from the model, e.g. `Column.value("Pages") { it.props.pages.value }`.
         * The key is [header] in lower case with dashes, unless [key] is given.
         *
         * The type the [selector] returns decides how it can be sorted and filtered: strings, numbers, booleans,
         * enums, `Instant`, `LocalDate`, `Duration`, `ModelID`s and the corresponding [DataContainer]s are both, other
         * `Comparable`s can only be sorted, and anything else neither. If the [selector] throws
         * [AuthorizationException], the value counts as unreadable: it matches no filter and is sorted last.
         */
        public inline fun <T : Any, reified R> value(
            header: String,
            key: String? = null,
            sortable: Boolean = true,
            filterable: Boolean = true,
            noinline selector: (Model<T>) -> R,
        ): Column<T> = valueColumn(header, key, typeOf<R>(), sortable, filterable, selector)

        @PublishedApi
        internal fun <T : Any> propertyColumn(
            kClass: KClass<*>,
            propertyName: String,
            header: String?,
            key: String?,
            sortable: Boolean,
            filterable: Boolean,
        ): Column<T> {
            val field = requireNotNull(ObjectSchema.of(kClass).field(propertyName)) {
                "'$propertyName' is not a constructor property of ${kClass.simpleName}"
            }
            return textColumn(
                header = header ?: field.prettyName,
                key = key ?: field.name,
                kind = kindOf(field),
                nullable = field.isNullable,
                sortable = sortable,
                filterable = filterable,
                value = { model -> normalize(field.get(model.props)) },
                text = { model -> renderText(field.get(model.props)) },
            )
        }

        @PublishedApi
        internal fun <T : Any> valueColumn(
            header: String,
            key: String?,
            type: KType,
            sortable: Boolean,
            filterable: Boolean,
            selector: (Model<T>) -> Any?,
        ): Column<T> {
            val kClass = type.classifier as? KClass<*>
            return textColumn(
                header = header,
                key = key ?: requireNotNull(slug(header).takeIf { it.isNotEmpty() }) {
                    "Cannot make a key from the header '$header'. Give the column a key."
                },
                kind = kClass?.let { kindOf(it) },
                nullable = type.isMarkedNullable,
                sortable = sortable,
                filterable = filterable,
                value = { model ->
                    try {
                        normalize(selector(model))
                    } catch (_: AuthorizationException) {
                        Unreadable
                    }
                },
                text = { model ->
                    try {
                        renderText(selector(model))
                    } catch (_: AuthorizationException) {
                        MASKED_TEXT
                    }
                },
            )
        }

        private fun <T : Any> textColumn(
            header: String,
            key: String?,
            kind: ColumnKind?,
            nullable: Boolean,
            sortable: Boolean,
            filterable: Boolean,
            value: ((Model<T>) -> Any?)?,
            text: (Model<T>) -> String,
        ): Column<T> {
            key?.let { requireValidKey(it) }
            return Column(header, key, kind, nullable, value, sortable, filterable, text) { model -> +text(model) }
        }
    }
}

/** Marks a value the actor may not read. */
internal object Unreadable

private const val MASKED_TEXT = "***"

private val keyPattern = Regex("[A-Za-z0-9_-]+")

internal fun requireValidKey(key: String) {
    require(key.matches(keyPattern)) { "The column key '$key' may only contain letters, digits, '_' and '-'" }
}

private fun slug(header: String): String = header.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

/** The value to sort and filter on: containers unwrapped, ids as their number. */
private fun normalize(value: Any?): Any? = when (value) {
    null -> null
    is DataContainer<*> -> value.valueOrNullIfNotAuthorized ?: Unreadable
    is ModelID<*> -> value.value
    else -> value
}

private fun renderText(value: Any?): String = when (value) {
    null -> ""
    is Collection<*> -> value.joinToString(", ") { renderText(it) }
    is DataContainer<*> -> renderTemporalContainer(value)?.replace('-', NON_BREAKING_HYPHEN_CHAR) ?: value.toString()
    is Instant -> formatTime(value).replace('-', NON_BREAKING_HYPHEN_CHAR)
    is LocalDate -> dateFormatter.format(value.atTime(0, 0)).replace('-', NON_BREAKING_HYPHEN_CHAR)
    else -> value.toString()
}

private fun formatDate(instant: Instant): String = dateFormatter
    .format(instant.toLocalDateTime(TimeZone.currentSystemDefault()))
    .replace('-', NON_BREAKING_HYPHEN_CHAR)

private const val NON_BREAKING_HYPHEN_CHAR = '‑'

/** How a column's values are sorted, filtered and parsed from query parameters. */
internal sealed class ColumnKind {
    open val sortable: Boolean = true
    open val filterable: Boolean = true

    /** Whether values can be picked one by one ("any of"). */
    open val hasAnyOf: Boolean = true
    open val hasRange: Boolean = false
    open val hasContains: Boolean = false

    /** The value [text] stands for, in the form [matchKey] gives, or null if it is not a valid value. */
    abstract fun parse(text: String): Any?

    /** What a value is compared with when filtering. */
    open fun matchKey(value: Any): Any = value

    /** What a value is compared with when sorting. */
    open fun sortKey(value: Any): Comparable<Any> {
        @Suppress("UNCHECKED_CAST")
        return value as Comparable<Any>
    }

    object Text : ColumnKind() {
        override val hasContains = true
        override fun parse(text: String): Any = text
    }

    class Numeric(private val parser: (String) -> Any?) : ColumnKind() {
        override val hasRange = true
        override fun parse(text: String): Any? = parser(text)
    }

    object InstantKind : ColumnKind() {
        override val hasAnyOf = false
        override val hasRange = true
        override fun parse(text: String): Any? = parseInstant(text)
    }

    object DateKind : ColumnKind() {
        override val hasRange = true
        override fun parse(text: String): Any? = runCatching { LocalDate.parse(text) }.getOrNull()
    }

    /** Filtered in seconds. */
    object DurationKind : ColumnKind() {
        override val hasAnyOf = false
        override val hasRange = true
        override fun parse(text: String): Any? = text.toDoubleOrNull()?.seconds
    }

    /** One of a few known values, sorted in the order of [options]. */
    class Choice(val options: List<String>) : ColumnKind() {
        override fun parse(text: String): Any? = text.takeIf { it in options }
        override fun matchKey(value: Any): Any = (value as? Enum<*>)?.name ?: value.toString()
        override fun sortKey(value: Any): Comparable<Any> {
            @Suppress("UNCHECKED_CAST")
            return options.indexOf(matchKey(value)) as Comparable<Any>
        }
    }

    /** A [Choice] among the model's states, which only the table knows. */
    object State : ColumnKind() {
        override fun parse(text: String): Any? = error("State is resolved into a Choice by the table")
    }

    /** A model id, as its number. Ids carry no order. */
    object Reference : ColumnKind() {
        override val sortable = false
        override fun parse(text: String): Any? = text.toIntOrNull()
    }

    object SortOnly : ColumnKind() {
        override val filterable = false
        override fun parse(text: String): Any? = null
    }
}

private fun parseInstant(text: String): Instant? = runCatching { Instant.parse(text) }.getOrNull()
    ?: runCatching { LocalDateTime.parse(text).toInstant(TimeZone.currentSystemDefault()) }.getOrNull()
    ?: runCatching { dateTimeLocalFormat.parse(text).toInstant(TimeZone.currentSystemDefault()) }.getOrNull()

private fun numberParser(type: PropertyType): ((String) -> Any?)? = when (type) {
    PropertyType.Int -> String::toIntOrNull
    PropertyType.Long -> String::toLongOrNull
    PropertyType.Short -> String::toShortOrNull
    PropertyType.Byte -> String::toByteOrNull
    PropertyType.UInt -> String::toUIntOrNull
    PropertyType.ULong -> String::toULongOrNull
    PropertyType.UShort -> String::toUShortOrNull
    PropertyType.UByte -> String::toUByteOrNull
    PropertyType.Float -> String::toFloatOrNull
    PropertyType.Double -> String::toDoubleOrNull
    else -> null
}

private fun kindOf(type: PropertyType, enumConstants: () -> List<Enum<*>>): ColumnKind? =
    numberParser(type)?.let { ColumnKind.Numeric(it) } ?: when (type) {
        PropertyType.String -> ColumnKind.Text
        PropertyType.Boolean -> ColumnKind.Choice(listOf("false", "true"))
        PropertyType.Enum -> ColumnKind.Choice(enumConstants().map { it.name })
        PropertyType.Instant -> ColumnKind.InstantKind
        PropertyType.Date -> ColumnKind.DateKind
        PropertyType.Duration -> ColumnKind.DurationKind
        PropertyType.Ref -> ColumnKind.Reference
        else -> null
    }

private fun kindOf(field: SchemaField): ColumnKind? {
    if (field.isCollection || field.nestedSchema != null) {
        return null
    }
    return field.type?.let { type -> kindOf(type) { field.enumConstants } }
}

private val plainTypes: Map<KClass<*>, PropertyType> = mapOf(
    String::class to PropertyType.String,
    Int::class to PropertyType.Int,
    Long::class to PropertyType.Long,
    Short::class to PropertyType.Short,
    Byte::class to PropertyType.Byte,
    UInt::class to PropertyType.UInt,
    ULong::class to PropertyType.ULong,
    UShort::class to PropertyType.UShort,
    UByte::class to PropertyType.UByte,
    Float::class to PropertyType.Float,
    Double::class to PropertyType.Double,
    Boolean::class to PropertyType.Boolean,
    Instant::class to PropertyType.Instant,
    LocalDate::class to PropertyType.Date,
    Duration::class to PropertyType.Duration,
    ModelID::class to PropertyType.Ref,
)

private val containerTypes: List<Pair<Class<*>, PropertyType>> = listOf(
    StringContainer::class.java to PropertyType.String,
    IntContainer::class.java to PropertyType.Int,
    LongContainer::class.java to PropertyType.Long,
    ShortContainer::class.java to PropertyType.Short,
    ByteContainer::class.java to PropertyType.Byte,
    UIntContainer::class.java to PropertyType.UInt,
    ULongContainer::class.java to PropertyType.ULong,
    UShortContainer::class.java to PropertyType.UShort,
    UByteContainer::class.java to PropertyType.UByte,
    FloatContainer::class.java to PropertyType.Float,
    DoubleContainer::class.java to PropertyType.Double,
    BooleanContainer::class.java to PropertyType.Boolean,
    EnumContainer::class.java to PropertyType.Enum,
    InstantContainer::class.java to PropertyType.Instant,
    DateContainer::class.java to PropertyType.Date,
    DurationContainer::class.java to PropertyType.Duration,
)

private fun kindOf(kClass: KClass<*>): ColumnKind? {
    val java = kClass.java
    if (java.isEnum) {
        return ColumnKind.Choice(java.enumConstants.map { (it as Enum<*>).name })
    }
    val type = plainTypes[kClass] ?: containerTypes.firstOrNull { it.first.isAssignableFrom(java) }?.second
    if (type != null) {
        return kindOf(type) { enumConstantsOfContainer(java) }
    }
    return if (Comparable::class.java.isAssignableFrom(java)) ColumnKind.SortOnly else null
}

/** The constants of the enum an [EnumContainer] subclass wraps. */
private fun enumConstantsOfContainer(container: Class<*>): List<Enum<*>> {
    var current: Class<*>? = container
    while (current != null) {
        val superType = current.genericSuperclass
        if (superType is ParameterizedType && superType.rawType == EnumContainer::class.java) {
            val enumClass = superType.actualTypeArguments.single() as Class<*>
            return enumClass.enumConstants.map { it as Enum<*> }
        }
        current = current.superclass
    }
    return emptyList()
}
