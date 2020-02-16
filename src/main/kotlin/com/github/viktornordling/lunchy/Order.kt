package com.github.viktornordling.lunchy

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.prompt
import org.apache.http.client.methods.RequestBuilder
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.util.EntityUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.File
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

class Order : CliktCommand(name = "order", help = "Order food") {
    private val cookie: String by option(help = "Cookie").prompt("Cookie")
    private val userAgent: String by option(help = "User agent").prompt("User agent")
    private val excludedSideDishes: String? by option(help = "Side dishes to never order, comma separated <id>s.")
    private val mondaySideDish: String? by option(help = "Forced side dish <id> on Mondays")
    private val tuesdaySideDish: String? by option(help = "Forced side dish <id> on Tuesdays")
    private val wednesdaySideDish: String? by option(help = "Forced side dish <id> on Wednesdays")
    private val thursdaySideDish: String? by option(help = "Forced side dish <id> on Thursdays")
    private val fridaySideDish: String? by option(help = "Forced side dish <id> on Fridays")
    private val startDate: String by option(help = "Start date").prompt("Start date (yyyy-mm-dd)")
    private val preferenceFile: String? by option(help = "File containing child's previous choices. If specified, order will be automatically placed if " +
            "preference can be deduced.")
    private var forcedSideDishMap: Map<String, Int?> = mapOf()
    private val excludedSideDishSet: Set<Int> = excludedSideDishes?.split(",")?.map { it.toInt() }?.toSet() ?: emptySet()

    override fun run() {
        forcedSideDishMap = mapOf(
                "monday" to mondaySideDish?.toInt(),
                "tuesday" to tuesdaySideDish?.toInt(),
                "wednesday" to wednesdaySideDish?.toInt(),
                "thursday" to thursdaySideDish?.toInt(),
                "friday" to fridaySideDish?.toInt()
        )
        println("wed: $wednesdaySideDish parsed wed: ${wednesdaySideDish?.toInt()}")
        val preferences = getPreferences(preferenceFile)
        val httpClient = HttpClientBuilder.create().build()
        var date: LocalDate = LocalDate.parse(startDate, DateTimeFormatter.ISO_DATE)!!
        var orderMore = true
        var daysOrdered = 0
        while (orderMore && daysOrdered < 13) {
            orderMore = orderFoodForDate(date, preferences, httpClient)
            date = getNextDate(date)
            daysOrdered++
        }
    }

    private fun getPreferences(preferenceFile: String?): List<Preference>? {
        if (preferenceFile != null) {
            val lines: List<String> = File(preferenceFile).readLines()
            return lines.map { line -> parseLine(line) }
        }
        return null
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

    private fun getNextDate(date: LocalDate): LocalDate {
        val weekEndDays = setOf(SATURDAY, SUNDAY)
        var tempDate = date.plusDays(1)
        while (weekEndDays.contains(tempDate.dayOfWeek)) {
            tempDate = tempDate.plusDays(1)
        }
        return tempDate
    }

    private fun orderFoodForDate(date: LocalDate, preferences: List<Preference>?, httpClient: CloseableHttpClient): Boolean {
        val dateString = date.format(DateTimeFormatter.ISO_DATE)
        val foodOptionsRequest = RequestBuilder
                .get()
                .addHeader("Cookie", cookie)
                .addHeader("User-Agent", userAgent)
                .setUri("https://edu.kiwikitchen.com/index.php/order/add/$dateString")
                .build()
        val foodOptions = httpClient.execute(foodOptionsRequest)

        val response = EntityUtils.toString(foodOptions.entity)
        val jsoup = Jsoup.parse(response)
        val mainDishDiv = jsoup.select("#maindish").first()
        if (mainDishDiv == null) {
            println("Could not find maindish, check your cookie and user-agent.")
            return false
        }
        val mainDishes = mainDishDiv.children().drop(1)

        println("Current date: $dateString (${date.dayOfWeek.toString().toLowerCase().capitalize()})")
        val orderedOptions = mainDishes.mapIndexed { index, option -> Pair(index + 1, option.text()) }
        orderedOptions.forEach { print("${it.first}: ${it.second}") }
        val preference = findPreferenceToOrder(preferences, mainDishes)
        val choice: Int = if (preference == null) {
            print("Enter main dish choice (0 to quit): ")
            readLine().orEmpty().toInt()
        } else {
            print("Automatically ordering $preference")
            orderedOptions.find { it.second == preference }?.first ?: 0
        }
        if (choice == 0) {
            return false
        }
        val mainDishId = mainDishes[choice - 1].attr("value")

        val sideElements = jsoup.select("#sidedish").first().children().drop(1)
        sideElements.forEachIndexed { index, option -> println("${index + 1}: ${option.text()} (${option.attr("value")})") }
        val sides = sideElements.map { Side(it.attr("value")!!.toInt(), it.text()) }.toSet()

        val today = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US).toLowerCase()
        println("Today = $today")
        if (forcedSideDishMap[today] != null) {
            println("forced map contains value for $today: ${forcedSideDishMap[today]} ")
        } else {
            println("forced map does not contain value for $today. Map is: $forcedSideDishMap")
        }
        val sideId: Int = forcedSideDishMap.getOrElse(today, { selectRandomSideDish(sides) })!!
        println("Ordering sideId: $sideId")
//        print("Enter side dish choice: ")
//        val side: Int = readLine().orEmpty().toInt()
//        val sideId = sides[side - 1].attr("value")

        val postRequest = RequestBuilder
                .post()
                .setUri("https://edu.kiwikitchen.com/index.php/order/add/$dateString")
                .addHeader("Cookie", cookie)
                .addHeader("User-Agent", userAgent)
                .addParameter("maindish", mainDishId)
                .addParameter("maindishsize", "2")
                .addParameter("sidedish", sideId.toString())
                .addParameter("formbtn", "add")
                .build()
        val orderResult = httpClient.execute(postRequest)
        if (orderResult.statusLine.statusCode != 302) {
            println("Order failed.")
            return false
        }
        println("$mainDishes, $mainDishId")
        return true
    }

    private fun selectRandomSideDish(sides: Set<Side>): Int {
        println("Excluded set: $excludedSideDishSet. Sides: ${sides.map { it.id }}")
        val subset = sides.filter { !excludedSideDishSet.contains(it.id) }
        println("Picking random dish from $subset")
        return subset.random().id
    }

    private fun findPreferenceToOrder(preferences: List<Preference>?, mainDishes: List<Element>): String? {
        if (preferences != null) {
            val preference = findPreference(preferences, mainDishes.map { it.text() })
            return if (preference == null) {
                println("Couldn't determine preference.")
                null
            } else {
                println("Automatically ordering option $preference")
                preference
            }
        }
        return null
    }

    private fun findPreference(preferences: List<Preference>, mainDishes: List<String>): String? {
        val allOptions: Set<Option> = preferences.flatMap { preference -> preference.options }.toSet()
        // Maps preference to a list of preferences (over which the mapped key was preferable).
        val preferenceMap: Map<Int, List<Preference>> = preferences.groupBy { it.preferredChoice }

        val mainDishesAsOptions: List<Option> = mainDishes.mapNotNull { mainDish -> allOptions.find { it.name == mainDish } }
        if (mainDishesAsOptions.size != mainDishes.size) {
            return null
        }

        val optionIds = mainDishesAsOptions.map { it.id }

        // Perform a dfs from each option, and see if we find all other options from that option.
        val winningOptions: MutableList<Option> = mutableListOf()
        for (option in mainDishesAsOptions) {
            val seenOptions = dfs(option, preferenceMap)
            if (seenOptions.containsAll(optionIds)) {
                winningOptions.add(option)
            }
        }
        if (winningOptions.isEmpty()) {
            return null
        }
        if (winningOptions.size > 0) {
            println("Multiple possible winning options found: $winningOptions. Picking a random one.")
        }
        return winningOptions.random().name
    }

    private fun dfs(currentOption: Option,
                    preferenceMap: Map<Int, List<Preference>>,
                    seenOptions: Set<Int> = setOf(),
                    sequenceOfPreferences: List<Pair<Option, Option>> = emptyList()): Set<Int> {
        val newSeen: Set<Int> = seenOptions + currentOption.id
        if (seenOptions.contains(currentOption.id)) {
            // Cycle detected.
            return newSeen
        }
        val childOptions = preferenceMap[currentOption.id].orEmpty().flatMap { it.options }.filter { it.id != currentOption.id }
        val seenSubOptions: Set<Int> = childOptions.flatMap { dfs(it, preferenceMap, newSeen, sequenceOfPreferences + Pair(currentOption, it)) }.toSet()
        return seenSubOptions + newSeen
    }

}
