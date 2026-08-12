package dev.omakey.core.predict

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.omakey.core.db.OmakeyDatabase
import dev.omakey.core.db.WordEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FrequencyNgramPredictionEngineTest {

    private lateinit var db: OmakeyDatabase
    private lateinit var engine: FrequencyNgramPredictionEngine

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), OmakeyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        engine = FrequencyNgramPredictionEngine(db.wordDao(), db.bigramDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `suggestNext ranks by frequency when no previous word`() = runTest {
        db.wordDao().upsert(WordEntity("hello", frequency = 5, isUserAdded = false, lastUsedTimestamp = 0))
        db.wordDao().upsert(WordEntity("help", frequency = 10, isUserAdded = false, lastUsedTimestamp = 0))
        db.wordDao().upsert(WordEntity("held", frequency = 1, isUserAdded = false, lastUsedTimestamp = 0))

        val suggestions = engine.suggestNext(previousWord = null, currentPrefix = "hel", limit = 3)

        assertEquals(listOf("help", "hello", "held"), suggestions)
    }

    @Test
    fun `recordAcceptedWord upserts frequency correctly across repeated calls`() = runTest {
        engine.recordAcceptedWord("test", previousWord = null)
        engine.recordAcceptedWord("test", previousWord = null)
        engine.recordAcceptedWord("test", previousWord = null)

        val entity = db.wordDao().findExact("test")
        assertEquals(3, entity?.frequency)
    }

    @Test
    fun `bigram suggestions are preferred over plain frequency and reorder as counts change`() = runTest {
        db.wordDao().upsert(WordEntity("world", frequency = 100, isUserAdded = false, lastUsedTimestamp = 0))
        db.wordDao().upsert(WordEntity("wide", frequency = 1, isUserAdded = false, lastUsedTimestamp = 0))

        // "wide" is far less frequent globally, but repeatedly follows "hello" -> bigram should surface it first
        repeat(5) { engine.recordAcceptedWord("wide", previousWord = "hello") }

        val suggestions = engine.suggestNext(previousWord = "hello", currentPrefix = "w", limit = 2)

        assertEquals("wide", suggestions.first())
    }

    @Test
    fun `bigramRank returns the stored count for a seen pair and 0 for an unseen one`() = runTest {
        repeat(3) { engine.recordAcceptedWord("is", previousWord = "this") }

        assertEquals(3, engine.bigramRank("this", "is"))
        assertEquals(0, engine.bigramRank("this", "banana"))
    }
}
