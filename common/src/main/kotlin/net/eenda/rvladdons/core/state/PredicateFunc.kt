package net.eenda.rvladdons.core.state

class PredicateFunc(private val function: () -> Boolean) : IPredicate {
    override fun evaluate(): Boolean = function()

    companion object {
        val True = PredicateFunc { true }
        val False = PredicateFunc { false }
    }
}
