package net.eenda.rvladdons.core.state

interface ITransition {
    val to: IState
    val condition: IPredicate
}
