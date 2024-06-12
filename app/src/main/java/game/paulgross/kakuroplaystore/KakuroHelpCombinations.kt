package game.paulgross.kakuroplaystore

// The helpers map the number of squares and the total to the row and column for every puzzle square.
// http://www.puzzles.grosse.is-a-geek.com/kaklista.html
data class HelpCombination(val size: Int, val total: Int)

var helpCombinationsLookup = mutableMapOf<HelpCombination, List<List<Int>>> (
    // ... 2s first
/*    HelpCombination(2, 3) to listOf(listOf(1, 2)),
    HelpCombination(2, 4) to listOf(listOf(1, 3)),
    HelpCombination(2, 5) to listOf(listOf(1, 4), listOf(2, 3)),
    HelpCombination(2, 6) to listOf(listOf(1, 5), listOf(2, 4)),
    HelpCombination(2, 7) to listOf(listOf(1, 6), listOf(2, 5), listOf(3, 4)),
    HelpCombination(2, 8) to listOf(listOf(1, 7), listOf(2, 6), listOf(3, 5)),
    HelpCombination(2, 9) to listOf(listOf(1, 8), listOf(2, 7), listOf(3, 6), listOf(4, 5)),
    HelpCombination(2, 10) to listOf(listOf(1, 9), listOf(2, 8), listOf(3, 7), listOf(4, 6)),
    HelpCombination(2, 11) to listOf(listOf(2, 9), listOf(3, 8), listOf(4, 7), listOf(5, 6)),
    HelpCombination(2, 12) to listOf(listOf(3, 9), listOf(4, 8), listOf(5, 7)),
    HelpCombination(2, 13) to listOf(listOf(4, 9), listOf(5, 8), listOf(6, 7)),
    HelpCombination(2, 14) to listOf(listOf(5, 9), listOf(6, 8)),
    HelpCombination(2, 15) to listOf(listOf(6, 9), listOf(7, 8)),
    HelpCombination(2, 16) to listOf(listOf(7, 9)),
    HelpCombination(2, 17) to listOf(listOf(8, 9)),

    // ... and the rest
    HelpCombination(3, 6) to listOf(listOf(1, 2, 3)),
    HelpCombination(3, 7) to listOf(listOf(1, 2, 4)),
    HelpCombination(3, 8) to listOf(listOf(1, 2, 5), listOf(1, 3, 4)),
    // ... etc
    HelpCombination(3, 24) to listOf(listOf(7, 8, 9)),
    HelpCombination(3, 23) to listOf(listOf(6, 8, 9)),
    // ... etc
    HelpCombination(8, 44) to listOf(listOf(2, 3, 4, 5, 6, 7, 8, 9)),
    HelpCombination(9, 45) to listOf(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9))*/
)

/*
Decode strings to create all pre-built HelpCombinations
"size/total:number,number...|number,number...|..."

eg: "2/5:14 23"
 */

private val helpCombinationsList = mutableListOf<String>()

// TODO - load asset HelpCombinations.txt and use to populate helpCombinationsList
// by calling createAllHelpCombinations() on each line.

fun createAllHelpCombinations() {
    for (currString in helpCombinationsList) {
        // Split the combination from the number lists.
        val entrySplit = currString.split(":")
        val combination = entrySplit[0]
        val combinationSplit = combination.split("/")
        val size = combinationSplit[0].toInt()
        val total = combinationSplit[1].toInt()
        val hc = HelpCombination(size, total)

        helpCombinationsLookup[hc] = splitHelpCombinations(entrySplit[1])
    }
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

// TODO:
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