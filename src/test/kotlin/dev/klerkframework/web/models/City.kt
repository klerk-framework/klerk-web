package dev.klerkframework.web.models

import dev.klerkframework.klerk.EventVisibility
import dev.klerkframework.klerk.VoidEventArgs
import dev.klerkframework.klerk.VoidEventNoParameters
import dev.klerkframework.klerk.statemachine.StateMachine
import dev.klerkframework.klerk.statemachine.stateMachine
import dev.klerkframework.web.config.Context
import dev.klerkframework.web.config.FirstName
import dev.klerkframework.web.config.MyCollections

data class City(val name: FirstName)

enum class CityStates {
    Updatable,
}

fun cityStateMachine(views: MyCollections): StateMachine<City, CityStates, Context, MyCollections> = stateMachine {
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

object CreateCity : VoidEventNoParameters<City>(EventVisibility.External)

fun newCity(args: VoidEventArgs<City, Nothing?, Context, MyCollections>): City = City(FirstName("Stockholm"))
