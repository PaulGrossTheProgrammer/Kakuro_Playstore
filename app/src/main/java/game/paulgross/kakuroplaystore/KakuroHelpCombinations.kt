package game.paulgross.kakuroplaystore

import java.io.BufferedReader

// The helpers map the number of squares and the total to the row and column for every puzzle square.
// http://www.puzzles.grosse.is-a-geek.com/kaklista.html
data class HelpCombination(val size: Int, val total: Int)

var helpCombinationsLookup = mutableMapOf<HelpCombination, List<List<Int>>> ()

/*
Decode strings in assets/HelpCombinations.txt to create all pre-built HelpCombinations
"size/total:digits<space>digits<space>..."

eg: "2/5:14 23"
 */

fun createAllHelpCombinations(reader: BufferedReader) {
    reader.forEachLine { line ->
        val entrySplit = line.split(":")
        val combination = entrySplit[0]
        val combinationSplit = combination.split("/")
        val size = combinationSplit[0].toInt()
        val total = combinationSplit[1].toInt()
        val hc = HelpCombination(size, total)

        helpCombinationsLookup[hc] = splitHelpCombinations(entrySplit[1])
    }

    println("Loaded ${helpCombinationsLookup.size} combinations with help sets")
}

fun splitHelpCombinations(lineString: String): List<List<Int>> {
    val lists = mutableListOf<List<Int>>()
    val groups = lineString.split(" ")
    for (group in groups) {
        val numberList = mutableListOf<Int>()

        for (digit in group) {
            val number = digit.toString().toInt()
            numberList.add(number)
        }
        lists.add(numberList)
    }
    return lists
}

fun getHelpSets(size: Int, total: Int): List<List<Int>>? {
    return helpCombinationsLookup[HelpCombination(size, total)]
}

data class HelpSets(var indexLookup: MutableMap<Int, List<List<Int>>> = mutableMapOf())

// String encoding/decoding dividers:
// "/" between each index
// "-" between the index and its list
// "|" between each group
// ":" between each number in the group.
// Example:
//      "0-1:2/1-1:2:5|1:3:4"
// Meaning
//      Index 0 can only have 1 and 2
//      Index 1 can have 1, 2 and 5, or 1, 3 and 4

fun encodeHelpSet(helpSet: HelpSets): String {

    val builder = StringBuilder()

    helpSet.indexLookup.forEach { index, lists ->
        if (builder.isNotEmpty()) { builder.append("/") }
        builder.append("$index-")

        val listBuilder = StringBuilder()
        lists.forEach { list ->
            if (listBuilder.isNotEmpty()) { listBuilder.append("|") }

            val itemListBuilder = StringBuilder()
            list.forEach { number ->
                if (itemListBuilder.isNotEmpty()) { itemListBuilder.append(":") }
                itemListBuilder.append("$number")
            }
            listBuilder.append(itemListBuilder.toString())
        }
        builder.append(listBuilder.toString())
    }

    return builder.toString()
}

fun decodeHelpSet(helpSetString: String): HelpSets {
    val helpSet = HelpSets()

    val indexList = helpSetString.split("/")
    indexList.forEach { indexHelper ->
        val indexSplit = indexHelper.split("-")
        val index = indexSplit[0].toInt()
        val allLists = indexSplit[1]
        val listOfLists = allLists.split("|")
        val allIndexLists = mutableListOf<List<Int>>()
        listOfLists.forEach { list ->
            val numberList = list.split(":")
            val helpNumbers = mutableListOf<Int>()
            numberList.forEach { numberString ->
                helpNumbers.add(numberString.toInt())
            }
            allIndexLists.add(helpNumbers)
        }
        helpSet.indexLookup[index] = allIndexLists
    }
    return helpSet
}