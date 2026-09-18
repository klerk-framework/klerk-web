package dev.klerkframework.web

import dev.klerkframework.klerk.KlerkTranslation
import dev.klerkframework.klerk.statemachine.StateMachine
import dev.klerkframework.klerk.statemachine.toDiagram

/**
 * Renders [stateMachine] as a [Mermaid](https://mermaid.js.org/) `stateDiagram-v2` definition, for
 * documentation/tooling. If [showUpdateNotes] is true, each state gets a note listing the events that update properties
 * without transitioning.
 */
public fun <V> generateStateDiagram(
    stateMachine: StateMachine<out Any, out Enum<*>, *, V>,
    showUpdateNotes: Boolean,
    translation: KlerkTranslation,
): String = buildString {
    val diagram = stateMachine.toDiagram(translation)
    appendLine("stateDiagram-v2")
    for (name in diagram.stateNames) {
        appendLine("${toVariable(name)}: $name")
    }
    for (transition in diagram.voidTransitions) {
        appendLine("[*] --> ${toVariable(transition.to!!)}: ${transition.label}")
    }
    for (transition in diagram.transitions) {
        appendLine("${toVariable(transition.from!!)} --> ${toVariable(transition.to!!)}: ${transition.label}")
    }
    if (showUpdateNotes) {
        for ((stateName, updatingEvents) in diagram.updateNotesByState) {
            appendLine("note left of ${toVariable(stateName)}")
            appendLine("    Events that updates properties:")
            for (name in updatingEvents) {
                appendLine("    • $name")
            }
            appendLine("end note")
        }
    }
    for (transition in diagram.deleteTransitions) {
        appendLine("${toVariable(transition.from!!)} --> [*]: ${transition.label}")
    }
}

private fun toVariable(name: String): String = name.replace(" ", "").lowercase()
