package game.paulgross.kakuroplaystore

//import org.junit.jupiter.api.Assertions.*
import org.junit.Test

import org.junit.Assert.*

// The dividers:
// "/" between each index
// "-" between the index and its list
// "|" between each group
// ":" between each number in the group.

class KakuroHelpCombinationsKtTest {

    @Test
    fun encodeHelpSet() {
    }

    // TODO - make more decode test cases
    @Test
    fun decodeHelpSet_singleIndexSingleSet() {
        val encodedString = "0-1,2"

        val decodedSets = game.paulgross.kakuroplaystore.decodeHelpSet(encodedString)
        assert(decodedSets.indexLookup.size == 1)
        assert(decodedSets.indexLookup[0]?.size == 1)
        assert(decodedSets.indexLookup[0]?.get(0)?.get(0) == 1)
        assert(decodedSets.indexLookup[0]?.get(0)?.get(0) == 2)
    }

    @Test
    fun decodeHelpSet_complex() {
        val encodedString = "0-1,2/1-1:2:5|1:3:4"

        val decodedSets = game.paulgross.kakuroplaystore.decodeHelpSet(encodedString)
        assert(decodedSets.indexLookup.size == 2)
        assert(decodedSets.indexLookup[0]?.size == 1)
        assert(decodedSets.indexLookup[0]?.get(0)?.get(0) == 1)
        assert(decodedSets.indexLookup[0]?.get(0)?.get(0) == 2)

        assert(decodedSets.indexLookup[1]?.size == 2)
        assert(decodedSets.indexLookup[1]?.get(0)?.get(0) == 1)
        assert(decodedSets.indexLookup[1]?.get(0)?.get(1) == 2)
        assert(decodedSets.indexLookup[1]?.get(0)?.get(2) == 5)
        assert(decodedSets.indexLookup[1]?.get(1)?.get(0) == 1)
        assert(decodedSets.indexLookup[1]?.get(1)?.get(1) == 3)
        assert(decodedSets.indexLookup[1]?.get(1)?.get(2) == 4)
    }
}