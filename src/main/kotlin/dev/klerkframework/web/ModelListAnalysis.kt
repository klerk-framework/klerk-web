package dev.klerkframework.web

import dev.klerkframework.klerk.view.asSequenceOrThrow
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import io.ktor.server.application.*
import io.ktor.server.response.*
import kotlinx.html.*
import kotlin.reflect.KClass

internal suspend fun <T : Any, V, C : KlerkContext> renderListAnalysis(
    call: ApplicationCall,
    support: WebSupport<C, V>,
    klerk: Klerk<C, V>,
    kClass: KClass<out Any>
) {
    val context = support.contextProvider(call, klerk)
    val modelView = klerk.specification.modelViews(kClass)

    call.respond(klerk.read(context) {
        html {
            apply(support.layout.page("Analysis") {
                h1 { +"Details" }
                +kClass.toString()
                h2 { +"Views" }
                modelView.views.forEach { collection ->
                    h6 { +(collection.id.toString()) }
                    val groupedByState = with(this@read) { collection.asSequenceOrThrow() }.groupBy { it.state }
                    val countPerState = groupedByState.mapValues { (k, v) -> groupedByState[k]?.count() ?: 0 }
                    +"Total count: ${countPerState.values.sum()}"
                    ul {
                        groupedByState.keys.forEach {
                            li { +"${it}: ${countPerState[it]}" }
                        }
                    }
                }
            })
        }
    })
}
