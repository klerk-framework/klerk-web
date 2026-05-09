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

data class City(val name: FirstName)

enum class CityStates {
    Updatable,
}

fun cityStateMachine(views: MyCollections): StateMachine<City, CityStates, Context, MyCollections> =

    stateMachine {

        event(CreateCity) {
        }


        voidState {
            onEvent(CreateCity) {
                createModel(CityStates.Updatable, ::newCity)
            }

        }

        state(CityStates.Updatable) {

        }


    }

object CreateCity : VoidEventNoParameters<City>(City::class, EventVisibility.EXTERNAL)

fun newCity(args: ArgForVoidEvent<City, Nothing?, Context, MyCollections>): City {
    return City(FirstName("Stockholm"))
}
