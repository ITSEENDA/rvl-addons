package net.eenda.rvladdons.core.state

import org.junit.Assert.assertEquals
import org.junit.Test

class StateMachineTest {
    @Test
    fun transitionRunsExitEnterAndUpdatesTheNewState() {
        val events = mutableListOf<String>()
        val first = object : BaseState() {
            override fun onEnter() {
                events += "first-enter"
            }

            override fun onUpdate() {
                events += "first-update"
            }

            override fun onExit() {
                events += "first-exit"
            }
        }
        val second = object : BaseState() {
            override fun onEnter() {
                events += "second-enter"
            }

            override fun onUpdate() {
                events += "second-update"
            }
        }
        val machine = StateMachine()

        machine.setState(first)
        machine.addTransitionFrom(first, second, PredicateFunc.True)
        machine.update()

        assertEquals(
            listOf("first-enter", "first-exit", "second-enter", "second-update"),
            events
        )
        assertEquals(second, machine.currentState)
    }

    @Test
    fun globalTransitionHasPriorityAndFixedUpdateUsesCurrentState() {
        val events = mutableListOf<String>()
        val first = object : BaseState() {
            override fun onFixedUpdate() {
                events += "first-fixed"
            }
        }
        val second = object : BaseState() {
            override fun onEnter() {
                events += "second-enter"
            }

            override fun onFixedUpdate() {
                events += "second-fixed"
            }
        }
        val localTarget = object : BaseState() {}
        val machine = StateMachine()

        machine.setState(first)
        machine.addTransitionFrom(first, localTarget, PredicateFunc.True)
        machine.addTransition(second, PredicateFunc.True)
        machine.update()
        machine.fixedUpdate()

        assertEquals(listOf("second-enter", "second-fixed"), events)
        assertEquals(second, machine.currentState)
    }

    @Test
    fun transitionWaitsForItsPredicateToBecomeTrue() {
        var ready = false
        val first = object : BaseState() {}
        val second = object : BaseState() {}
        val machine = StateMachine()

        machine.setState(first)
        machine.addTransitionFrom(first, second, PredicateFunc { ready })
        machine.update()
        assertEquals(first, machine.currentState)

        ready = true
        machine.update()
        assertEquals(second, machine.currentState)
    }
}
