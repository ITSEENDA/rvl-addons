package net.eenda.rvladdons.core.state

data class Transition(
    override val to: IState,
    override val condition: IPredicate
) : ITransition
