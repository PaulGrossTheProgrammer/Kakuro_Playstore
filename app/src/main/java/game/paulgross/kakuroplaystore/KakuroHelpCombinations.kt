package game.paulgross.kakuroplaystore

// The helpers map the number of squares and the total to the row and column for every puzzle square.
// http://www.puzzles.grosse.is-a-geek.com/kaklista.html
data class HelpCombination(val size: Int, val total: Int)

val helpCombinationsLookup = mapOf<HelpCombination, List<Int>> (
    // ... 2s first
    HelpCombination(2, 3) to listOf(1, 2),
    HelpCombination(2, 4) to listOf(1, 3),
    // ... and the rest
    HelpCombination(3, 6) to listOf(1, 2, 3),
    HelpCombination(3, 7) to listOf(1, 2, 4),
    HelpCombination(3, 8) to listOf(1, 2, 5),
    HelpCombination(3, 8) to listOf(1, 3, 4),
    // ... etc
    HelpCombination(3, 24) to listOf(7, 8, 9),
    HelpCombination(3, 23) to listOf(6, 8, 9),
    // ... etc
    HelpCombination(9, 45) to listOf(1, 2, 3, 4, 5, 6, 7, 8, 9)
)

data class HelpSet(var indexLookup: MutableMap<Int, List<List<Int>>> = mutableMapOf())

fun encodeHelpSet(helpSet: HelpSet): String {
    return "TODO..."
}

// TODO:
fun decodeHelpSet(helpSetString: String): HelpSet {
    return HelpSet()
}