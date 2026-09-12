package net.eenda.rvladdons.core.state

abstract class BaseState : IState {
    override fun onEnter() = Unit

    override fun onUpdate() = Unit

    override fun onFixedUpdate() = Unit

    override fun onExit() = Unit
}
