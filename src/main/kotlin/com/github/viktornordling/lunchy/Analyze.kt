package com.github.viktornordling.lunchy

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.prompt
import org.jsoup.Jsoup
import java.io.File

data class Option(val id: Int, val name: String)
data class Side(val id: Int, val name: String)
data class OptionWithOrgId(val orgId: Int, val option: Option)
data class Preference(val preferredChoice: Int, val options: List<Option>)
data class Cycle(val description: String)

class Analyze : CliktCommand(name = "analyze", help = "Analyze choices") {
    private val fileName: String by option(help = "File name").prompt("File name")

    override fun run() {
        val lines: List<String> = File(fileName).readLines()
        val preferences: List<Preference> = lines.map { line -> parseLine(line) }
        val allOptions: Set<Option> = preferences.flatMap { preference -> preference.options }.toSet()
        // Maps preference to a list of preferences (over which the mapped key was preferable).
        val preferenceMap: Map<Int, List<Preference>> = preferences.groupBy { it.preferredChoice }

        // Perform a DFS from each option and see if we find a cycle (inconsistency).
        println("Number of options: ${allOptions.size}")
        val allCycles = allOptions.flatMap { dfs(it, preferenceMap) }
        allCycles.forEach { println("Child prefers ${it.description}") }
    }

    private fun dfs(currentOption: Option,
                    preferenceMap: Map<Int, List<Preference>>,
                    seenOptions: Set<Int> = setOf(),
                    sequenceOfPreferences: List<Pair<Option, Option>> = emptyList(),
                    cycles: Set<Cycle> = emptySet()): Set<Cycle> {
        if (seenOptions.contains(currentOption.id)) {
            val sequence = sequenceOfPreferences.joinToString(separator = ", ") { "${it.first.name} over ${it.second.name}" }
            return cycles + Cycle(sequence)
        }
        val newSeen: Set<Int> = seenOptions + currentOption.id
        val childOptions = preferenceMap[currentOption.id].orEmpty().flatMap { it.options }.filter { it.id != currentOption.id }
        val newCycles = childOptions.flatMap { dfs(it, preferenceMap, newSeen, sequenceOfPreferences + Pair(currentOption, it)) }
        return cycles + newCycles
    }

    private fun parseLine(line: String): Preference {
        val regex = Regex("value=\"(\\d+)\">([^<]+)<")
        val matches = regex.findAll(line)
        val options: List<OptionWithOrgId> = matches
                .map { Pair(it.groupValues[1],it.groupValues[2]) }
                .map { Pair(it.first.toInt(), it.second) }.toList()
                .map { OptionWithOrgId(orgId = it.first, option = Option(id = Jsoup.parse(it.second).text().hashCode(), name = Jsoup.parse(it.second).text())) }
        val orgChoice: Int = line.drop(line.lastIndexOf(", ")).drop(2).trim().toInt()
        val choice: OptionWithOrgId = options.find { it.orgId == orgChoice }!!
        return Preference(options = options.map { it.option }, preferredChoice = choice.option.id)
    }

}