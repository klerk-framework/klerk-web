package dev.klerkframework.web.models

import dev.klerkframework.klerk.ArgForVoidEvent
import dev.klerkframework.klerk.EventVisibility
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.VoidEventNoParameters
import dev.klerkframework.klerk.VoidEventWithParameters
import dev.klerkframework.klerk.statemachine.StateMachine
import dev.klerkframework.klerk.statemachine.stateMachine
import dev.klerkframework.web.config.Book
import dev.klerkframework.web.config.Context
import dev.klerkframework.web.config.FirstName
import dev.klerkframework.web.config.MyCollections

data class Publisher(val city: ModelID<City>)

enum class PublisherStates {
    Updatable,
}

fun publisherStateMachine(views: MyCollections): StateMachine<Publisher, PublisherStates, Context, MyCollections> =

    stateMachine {

        event(CreatePublisher) {
            validReferences(CreatePublisherParams::city, views.cities.all)
        }


        voidState {
            onEvent(CreatePublisher) {
                createModel(PublisherStates.Updatable, ::newPublisher)
            }

        }

        state(PublisherStates.Updatable) {

        }


    }

object CreatePublisher : VoidEventWithParameters<Publisher, CreatePublisherParams>(Publisher::class, EventVisibility.EXTERNAL, CreatePublisherParams::class)

fun newPublisher(args: ArgForVoidEvent<Publisher, CreatePublisherParams, Context, MyCollections>): Publisher {
    return Publisher(args.command.params.city)
}

data class CreatePublisherParams(val city: ModelID<City>)
