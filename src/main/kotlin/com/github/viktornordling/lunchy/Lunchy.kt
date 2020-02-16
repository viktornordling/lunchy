package com.github.viktornordling.lunchy

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands

class LunchyClient: CliktCommand(name = "lunchy") {
    override fun run() = Unit
}

object Lunchy {
    @JvmStatic
    fun main(args: Array<String>) = LunchyClient()
            .subcommands(Order(), Analyze())
            .main(args)
}
