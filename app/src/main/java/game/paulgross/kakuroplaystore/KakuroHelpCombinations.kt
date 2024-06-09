package game.paulgross.kakuroplaystore

// The helpers map the number of squares and the total to the row and column for every puzzle square.
// http://www.puzzles.grosse.is-a-geek.com/kaklista.html
data class HelpCombination(val size: Int, val total: Int)

val helpCombinationsLookup = mapOf<HelpCombination, List<List<Int>>> (
    // ... 2s first
    HelpCombination(2, 3) to listOf(listOf(1, 2)),
    HelpCombination(2, 4) to listOf(listOf(1, 3)),
    // ... and the rest
    HelpCombination(3, 6) to listOf(listOf(1, 2, 3)),
    HelpCombination(3, 7) to listOf(listOf(1, 2, 4)),
    HelpCombination(3, 8) to listOf(listOf(1, 2, 5), listOf(1, 3, 4)),
    // ... etc
    HelpCombination(3, 24) to listOf(listOf(7, 8, 9)),
    HelpCombination(3, 23) to listOf(listOf(6, 8, 9)),
    // ... etc
    HelpCombination(9, 45) to listOf(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9))
)

fun getHelpSets(size: Int, total: Int): List<List<Int>>? {
    return helpCombinationsLookup[HelpCombination(size, total)]
}

data class HelpSets(var indexLookup: MutableMap<Int, List<List<Int>>> = mutableMapOf())

fun encodeHelpSet(helpSet: HelpSets): String {
    // The dividers:
    // "/" between each index
    // "-" between the index and its list
    // "|" between each group
    // ":" between each number in the group.
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


    // TODO - this is just a fill-in for now. Replace with actual code later.
//    return "0-1:2:5|1:3:4/1-1:2:5|1:3:4"
}

// TODO:
fun decodeHelpSet(helpSetString: String): HelpSets {
    val helpSet = HelpSets()

    // TODO - this is just a fill-in for now. Replace with actual code later.
    helpSet.indexLookup[0] = listOf(listOf(1,2,5), listOf(1,3,4))
    helpSet.indexLookup[1] = listOf(listOf(1,2,5), listOf(1,3,4))

    return helpSet
}