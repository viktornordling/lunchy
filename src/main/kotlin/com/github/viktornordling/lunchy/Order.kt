package com.github.viktornordling.lunchy

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.prompt
import org.apache.http.client.methods.RequestBuilder
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.util.EntityUtils
import org.jsoup.Jsoup
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class Order : CliktCommand(name = "order", help = "Order food") {
    private val cookie: String by option(help = "Cookie").prompt("Cookie")
    private val userAgent: String by option(help = "User agent").prompt("User agent")
    private val startDate: String by option(help = "Start date").prompt("Start date (yyyy-mm-dd)")

    override fun run() {
        val httpClient = HttpClientBuilder.create().build()
        var date: LocalDate = LocalDate.parse(startDate, DateTimeFormatter.ISO_DATE)!!
        var orderMore = true
        while (orderMore) {
            orderMore = orderFoodForDate(date, httpClient)
            date = getNextDate(date)
        }
    }

    private fun getNextDate(date: LocalDate): LocalDate {
        val weekEndDays = setOf(SATURDAY, SUNDAY)
        var tempDate = date.plusDays(1)
        while (weekEndDays.contains(tempDate.dayOfWeek)) {
            tempDate = tempDate.plusDays(1)
        }
        return tempDate
    }

    private fun orderFoodForDate(date: LocalDate, httpClient: CloseableHttpClient): Boolean {
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
        mainDishes.forEachIndexed { index, option -> println("${index + 1}: ${option.text()}") }

        println("Current date: $dateString (${date.dayOfWeek.toString().toLowerCase().capitalize()})")
        print("Enter choice (0 to quit): ")
        val choice: Int = readLine().orEmpty().toInt()
        if (choice == 0) {
            return false
        }
        val mainDishId = mainDishes[choice - 1].attr("value")

        val sides = jsoup.select("#sidedish").first().children().drop(1)
        sides.forEachIndexed { index, option -> println("${index + 1}: ${option.text()}") }

        print("Enter choice: ")
        val side: Int = readLine().orEmpty().toInt()
        val sideId = sides[side - 1].attr("value")

        val postRequest = RequestBuilder
                .post()
                .setUri("https://edu.kiwikitchen.com/index.php/order/add/$dateString")
                .addHeader("Cookie", cookie)
                .addHeader("User-Agent", userAgent)
                .addParameter("maindish", mainDishId)
                .addParameter("maindishsize", "2")
                .addParameter("sidedish", sideId)
                .addParameter("formbtn", "add")
                .build()
        val orderResult = httpClient.execute(postRequest)
        if (orderResult.statusLine.statusCode != 302) {
            println("Order failed.")
            return false
        }
        return true
    }

}
