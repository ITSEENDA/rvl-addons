package net.eenda.rvladdons.core.state

class StateMachine {
    private class StateNode(val state: IState) {
        val transitions = LinkedHashSet<ITransition>()
    }

    private val states = LinkedHashMap<Class<out IState>, StateNode>()
    private val globalTransitions = LinkedHashSet<ITransition>()
    private var current: StateNode? = null

    val currentState: IState?
        get() = current?.state

    fun update() {
        getTransition()?.let { changeState(it.to) }
        current?.state?.onUpdate()
    }

    fun fixedUpdate() {
        current?.state?.onFixedUpdate()
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

    fun addTransitionFrom(from: IState, to: IState, condition: IPredicate) {
        getOrAddState(from).transitions += Transition(getOrAddState(to).state, condition)
    }

    fun addTransition(to: IState, condition: IPredicate) {
        globalTransitions += Transition(getOrAddState(to).state, condition)
    }

    private fun getTransition(): ITransition? {
        globalTransitions.firstOrNull { it.condition.evaluate() }?.let { return it }
        return current?.transitions?.firstOrNull { it.condition.evaluate() }
    }

    private fun getOrAddState(state: IState): StateNode =
        states.getOrPut(state::class.java) { StateNode(state) }
}
