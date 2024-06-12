package game.paulgross.kakuroplaystore

import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.nio.file.Paths


class KakuroHelpCombinationsKtTest {

    @Throws(IOException::class)
    private fun openFile(filename: String): InputStream {
        // TODO to FIXME: Need to test open classloader files and check that directory exists first
        val projectDirAbsolutePath = Paths.get("").toAbsolutePath().toString()
        println("projectDirAbsolutePath = $projectDirAbsolutePath")
        val assetsPath = Paths.get("source/main/assets").toAbsolutePath().toString()
        println("assetsPath = $assetsPath")

        println("filename = $filename")

        val theClassLoader = javaClass.classLoader
        println("theClassLoader = $theClassLoader")

        val theStream = theClassLoader.getResourceAsStream(filename)
        println("theStream = $theStream")

        return theStream
    }

    @Test
    fun testOpenProjectAsset() {
        // FIXME - this doesn't work
        openFile("./source/main/assets/HelpCombinations.txt")
    }

    @Test
    fun splitHelpCombinations_Example1() {
        val lists = splitHelpCombinations("123456789")

        assert(lists.size == 1)
        val list1 = lists[0]

        assert(list1.size == 9)
        assert(list1[0] == 1)
        assert(list1[1] == 2)
        assert(list1[2] == 3)
        assert(list1[3] == 4)
        assert(list1[4] == 5)
        assert(list1[5] == 6)
        assert(list1[6] == 7)
        assert(list1[7] == 8)
        assert(list1[8] == 9)
    }

    @Test
    fun splitHelpCombinations_Example2() {
        val lists = splitHelpCombinations("14 23")

        assert(lists.size == 2)

        val list1 = lists[0]
        val list2 = lists[1]

        assert(list1.size == 2)
        assert(list2.size == 2)

        assert(list1[0] == 1)
        assert(list1[1] == 4)
        assert(list2[0] == 2)
        assert(list2[1] == 3)
    }

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

        val decodedSets = decodeHelpSet(encodedString)
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