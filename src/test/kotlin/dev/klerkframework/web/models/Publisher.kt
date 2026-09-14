package dev.klerkframework.web.models

import dev.klerkframework.klerk.InstanceEventArgs
import dev.klerkframework.klerk.VoidEventArgs
import dev.klerkframework.klerk.EventVisibility
import dev.klerkframework.klerk.InstanceEventWithParameters
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.VoidEventNoParameters
import dev.klerkframework.klerk.VoidEventWithParameters
import dev.klerkframework.klerk.statemachine.StateMachine
import dev.klerkframework.klerk.statemachine.stateMachine
import dev.klerkframework.web.config.Book
import dev.klerkframework.web.config.Context
import dev.klerkframework.web.config.FirstName
import dev.klerkframework.web.config.MyCollections
import dev.klerkframework.web.config.NumberOfOffices

data class Publisher(
    val city: ModelID<City>,
    val numberOfOffices: NumberOfOffices,
    )

enum class PublisherStates {
    Updatable,
}

fun publisherStateMachine(views: MyCollections): StateMachine<Publisher, PublisherStates, Context, MyCollections> =

    stateMachine {

        event(CreatePublisher) {
            validReferences(CreatePublisherParams::city, views.cities.all)
        }

        event(UpdatePublisher) {
            validReferences(Publisher::city, views.cities.all)
        }

        voidState {
            onEvent(CreatePublisher) {
                createModel(PublisherStates.Updatable, ::newPublisher)
            }

        }

        state(PublisherStates.Updatable) {
            onEvent(UpdatePublisher) {
                update(::updatePublisher)
            }
        }


    }

object CreatePublisher : VoidEventWithParameters<Publisher, CreatePublisherParams>(EventVisibility.External)

object UpdatePublisher : InstanceEventWithParameters<Publisher, Publisher>(EventVisibility.External)

fun newPublisher(args: VoidEventArgs<Publisher, CreatePublisherParams, Context, MyCollections>): Publisher {
    return Publisher(args.command.params.city, NumberOfOffices(2))
}

fun updatePublisher(args: InstanceEventArgs<Publisher, Publisher, Context, MyCollections>): Publisher {
    return Publisher(args.command.params.city, args.command.params.numberOfOffices)
}

data class CreatePublisherParams(val city: ModelID<City>)
