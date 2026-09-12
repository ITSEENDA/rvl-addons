package net.eenda.rvladdons.core.state

interface IState {
    fun onEnter()
    fun onUpdate()
    fun onFixedUpdate()
    fun onExit()
}
