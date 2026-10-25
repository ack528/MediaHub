package com.localtg.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ViewerFeedTest {
    private fun item(id: String) = Item(id, "group", id, "jpg", "photo")

    @Test fun paginationKeepsMixedItemsAndDeduplicates() = runBlocking {
        var page = 0
        val feed = ViewerFeed(listOf(item("1"))) { _, _ ->
            if (page++ == 0) listOf(item("1"), item("2").copy(type = "video")) to "next"
            else listOf(item("3")) to null
        }
        assertTrue(feed.loadMore()); assertFalse(feed.exhausted)
        assertTrue(feed.loadMore()); assertTrue(feed.exhausted)
        assertEquals(listOf("1", "2", "3"), feed.items.map { it.id })
        assertEquals("video", feed.items[1].type)
        assertFalse(feed.loadMore()); assertEquals(2, page)
    }

    @Test fun cancellationDoesNotExhaustFeed() = runBlocking {
        var cancel = true
        val feed = ViewerFeed(listOf(item("1"))) { _, _ ->
            if (cancel) throw CancellationException("left viewer")
            listOf(item("2")) to null
        }
        try { feed.loadMore(); fail("cancellation swallowed") } catch (_: CancellationException) { }
        assertFalse(feed.exhausted)
        cancel = false; assertTrue(feed.loadMore()); assertTrue(feed.exhausted)
    }

    @Test fun simultaneousPrefetchAndAdvanceSerializePages() = runBlocking {
        var page = 1
        var active = 0
        val feed = ViewerFeed(listOf(item("0"))) { _, _ ->
            active++; assertEquals(1, active); delay(10)
            val next = page++; active--
            listOf(item(next.toString())) to if (next < 2) "next" else null
        }
        awaitAll(async { feed.loadMore() }, async { feed.loadMore() })
        assertEquals(listOf("0", "1", "2"), feed.items.map { it.id })
        assertTrue(feed.exhausted)
    }
}
