package net.eenda.rvladdons.core.state

class StateMachine {
    private data class Transition(val to: IState, val condition: () -> Boolean)

    private class StateNode(val state: IState) {
        val transitions = LinkedHashSet<Transition>()
    }

    private val states = LinkedHashMap<Class<out IState>, StateNode>()
    private val globalTransitions = LinkedHashSet<Transition>()
    private var current: StateNode? = null

    val currentState: IState?
        get() = current?.state

    fun update() {
        getTransition()?.let { changeState(it.to) }
        current?.state?.onUpdate()
    }

    fun setState(state: IState) {
        if (current == null) {
            current = getOrAddState(state)
            current?.state?.onEnter()
        } else {
            changeState(state)
        }
    }

    fun changeState(state: IState) {
        val next = getOrAddState(state)
        if (current?.state === next.state) return
        current?.state?.onExit()
        next.state.onEnter()
        current = next
    }

    fun addTransitionFrom(from: IState, to: IState, condition: () -> Boolean) {
        getOrAddState(from).transitions += Transition(getOrAddState(to).state, condition)
    }

    fun addTransition(to: IState, condition: () -> Boolean) {
        globalTransitions += Transition(getOrAddState(to).state, condition)
    }

    private fun getTransition(): Transition? {
        globalTransitions.firstOrNull { it.condition() }?.let { return it }
        return current?.transitions?.firstOrNull { it.condition() }
    }

    private fun getOrAddState(state: IState): StateNode =
        states.getOrPut(state::class.java) { StateNode(state) }
}
