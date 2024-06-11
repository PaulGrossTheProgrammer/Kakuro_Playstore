package game.paulgross.kakuroplaystore

import org.junit.Test

// The dividers:
// "/" between each index
// "-" between the index and its list
// "|" between each group
// ":" between each number in the group.

class KakuroHelpCombinationsKtTest {

    @Test
    fun getHelpSets_validate1() {
        val size = 2
        val total = 3
        val sets = getHelpSets(size, total)
        sets?.forEach { set ->
            var totalCheck = 0
            set.forEach { number ->
                totalCheck += number
            }
            assert(totalCheck == total)
        }
    }

    @Test
    fun getHelpSets_validate2() {
        val size = 9
        val total = 45
        val sets = getHelpSets(size, total)
        sets?.forEach { set ->
            var totalCheck = 0
            set.forEach { number ->
                totalCheck += number
            }
            assert(totalCheck == total)
        }
    }

    @Test
    fun getHelpSets_validate3() {
        val size = 8
        val total = 44
        val sets = getHelpSets(size, total)
        sets?.forEach { set ->
            var totalCheck = 0
            set.forEach { number ->
                totalCheck += number
            }
            assert(totalCheck == total)
        }
    }

    @Test
    fun encodeHelpSet_singleIndexSingleSet() {
        val helpSet = HelpSets()
        helpSet.indexLookup[0] = listOf(listOf(1,2))

        val resultString = encodeHelpSet(helpSet)
        assert(resultString == "0-1:2")
    }

    @Test
    fun decodeHelpSet_singleIndexSingleSet() {
        val encodedString = "0-1:2"

        val decodedSets = decodeHelpSet(encodedString)
        assert(decodedSets.indexLookup.size == 1)
        assert(decodedSets.indexLookup[0]?.size == 1)
        assert(decodedSets.indexLookup[0]?.get(0)?.get(0) == 1)
        assert(decodedSets.indexLookup[0]?.get(0)?.get(1) == 2)
    }

    @Test
    fun encodeHelpSet_twoIndexSets() {
        val helpSet = HelpSets()
        helpSet.indexLookup[0] = listOf(listOf(1,2))
        helpSet.indexLookup[1] = listOf(listOf(1,2,5), listOf(1,3,4))

        val resultString = encodeHelpSet(helpSet)
        assert(resultString == "0-1:2/1-1:2:5|1:3:4")
    }

    @Test
    fun decodeHelpSet_twoSets() {
        val encodedString = "0-1:2/1-1:2:5|1:3:4"

        val decodedSets = game.paulgross.kakuroplaystore.decodeHelpSet(encodedString)
        assert(decodedSets.indexLookup.size == 2)
        assert(decodedSets.indexLookup[0]?.size == 1)
        assert(decodedSets.indexLookup[0]?.get(0)?.get(0) == 1)
        assert(decodedSets.indexLookup[0]?.get(0)?.get(1) == 2)

        assert(decodedSets.indexLookup[1]?.size == 2)
        assert(decodedSets.indexLookup[1]?.get(0)?.get(0) == 1)
        assert(decodedSets.indexLookup[1]?.get(0)?.get(1) == 2)
        assert(decodedSets.indexLookup[1]?.get(0)?.get(2) == 5)
        assert(decodedSets.indexLookup[1]?.get(1)?.get(0) == 1)
        assert(decodedSets.indexLookup[1]?.get(1)?.get(1) == 3)
        assert(decodedSets.indexLookup[1]?.get(1)?.get(2) == 4)
    }
}