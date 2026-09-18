package dev.klerkframework.web

import dev.klerkframework.klerk.EventReference
import dev.klerkframework.klerk.misc.ObjectSchema
import kotlin.reflect.KClass

/** An event together with the [ObjectSchema] of its parameters class, e.g. to build a form for it. */
public data class EventWithParameters<T : Any>(val eventReference: EventReference, val parameters: ObjectSchema<T>) {
    /** Creates it from the parameters class itself. */
    public constructor(eventReference: EventReference, parametersClass: KClass<T>) :
        this(eventReference, ObjectSchema.of(parametersClass))
}
