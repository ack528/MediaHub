package com.localtg.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SlideshowTest {
    private fun item(id: Int, type: String = "photo") = Item("$id", "group", "$id", if (type == "video") "mp4" else "jpg", type)
    private class Library : SlideLibrary {
        val historyCalls = mutableListOf<Triple<SlideScope, SlideGroupOrder, String?>>()
        val shuffleCalls = mutableListOf<Triple<SlideScope, Long, String?>>()
        var onHistory: (SlideGroupOrder, String?, String?) -> HistoryResp = { _, _, _ -> HistoryResp(emptyList()) }
        var onShuffle: (String?) -> HistoryResp = { HistoryResp(emptyList()) }
        override suspend fun history(scope: SlideScope, order: SlideGroupOrder, cursor: String?, around: String?): HistoryResp {
            historyCalls += Triple(scope, order, around ?: cursor); return onHistory(order, cursor, around)
        }
        override suspend fun shuffle(scope: SlideScope, seed: Long, cursor: String?): HistoryResp {
            shuffleCalls += Triple(scope, seed, cursor); return onShuffle(cursor)
        }
    }

    @Test fun groupStartsAtCurrentAndReverseAppliesBeforePagination() = runBlocking {
        for ((mode, direction, expected) in listOf(Triple("forward", "asc", listOf("3", "4", "5", "6")), Triple("reverse", "desc", listOf("3", "2", "1", "0")))) {
            val library = Library()
            library.onHistory = { order, cursor, around ->
                assertEquals(direction, order.direction)
                if (cursor == null) { assertEquals("3", around); HistoryResp((if (direction == "asc") (1..5).toList() else (5 downTo 1).toList()).map { item(it) }, nextCursor = "next") }
                else { assertEquals("next", cursor); HistoryResp(listOf(item(if (direction == "asc") 6 else 0))) }
            }
            val source = SlideSource(SlideScope("photo,gif", dialog = "group"), SlideGroupOrder("name", "asc"))
            val feed = createSlideshow(source, library, item(3), SlideshowOptions(order = mode), 500)
            feed.loadMore()
            assertEquals(expected, feed.items.map { it.id }); assertTrue(feed.exhausted)
            assertEquals("group", feed.originDialogId)
            assertEquals("group" to expected.last(), feed.returnPosition(feed.items.last()))
            assertTrue(library.historyCalls.all { it.first.mediaTypes == "photo,gif" })
        }
    }

    @Test fun missingAnchorDoesNotSilentlyRestartAtBeginning() = runBlocking {
        val library = Library(); library.onHistory = { _, _, _ -> HistoryResp(listOf(item(1), item(2))) }
        try { createSlideshow(SlideSource(SlideScope("photo", dialog = "group"), SlideGroupOrder("name", "asc")), library, item(3), SlideshowOptions(), 500); fail("anchor ignored") }
        catch (_: IllegalStateException) { }
    }

    @Test fun randomPhotoSelectionNeverWidensToVideoAndKeepsCurrentItem() = runBlocking {
        val library = Library()
        library.onShuffle = { cursor -> if (cursor == null) HistoryResp(listOf(item(4), item(9, "video"), item(1)), nextCursor = "s2") else HistoryResp(listOf(item(7), item(2))) }
        val source = SlideSource(SlideScope("photo,gif", roots = "8"), sequence = SlideSequence(listOf(item(1), item(4))), origin = "random")
        val feed = createSlideshow(source, library, item(4), SlideshowOptions(order = "shuffle"), 500)
        feed.loadMore()
        assertEquals(listOf("4", "1", "7", "2"), feed.items.map { it.id })
        assertTrue(feed.items.all { it.type != "video" }); assertTrue(feed.exhausted)
        assertEquals("random" to "2", feed.returnPosition(feed.items.last()))
        assertTrue(library.shuffleCalls.all { it.first.roots == "8" && it.first.mediaTypes == "photo,gif" && it.second == 500L })
    }

    @Test fun groupShufflePinsStartAcrossPagingAndStopButNewOrdersFollowCurrent() = runBlocking {
        val library = Library()
        library.onShuffle = { cursor -> if (cursor == null) HistoryResp(listOf(item(8),item(9)),nextCursor="next") else HistoryResp(listOf(item(10))) }
        library.onHistory = { _,_,around -> HistoryResp(listOf(item(around!!.toInt()),item(11))) }
        val source=SlideSource(SlideScope("photo",dialog="group"),SlideGroupOrder("name","asc"))
        val random=createSlideshow(source,library,item(3),SlideshowOptions(order="shuffle"),500)
        random.loadMore()
        assertEquals("group" to "3",random.returnPosition(item(10)))
        val state=SlideshowPlayback();state.start(SlideshowOptions(order="shuffle"));state.pause();state.end();state.stop()
        assertEquals("group" to "3",random.returnPosition(item(10)))
        for(order in listOf("forward","reverse")) {
            val next=createSlideshow(random.slideSource!!,library,item(10),SlideshowOptions(order=order),501)
            assertEquals("group" to "11",next.returnPosition(item(11)))
        }
        val restarted=createSlideshow(random.slideSource!!,library,item(10),SlideshowOptions(order="shuffle"),502)
        assertEquals("group" to "10",restarted.returnPosition(item(9)))
    }

    @Test fun randomSequenceSupportsForwardAndReverseFromChosenPosition() = runBlocking {
        val source = SlideSource(SlideScope("video", roots = "3"), sequence = SlideSequence((1..5).map { item(it, "video") }))
        val a = createSlideshow(source, Library(), item(3, "video"), SlideshowOptions(), 500)
        val b = createSlideshow(source, Library(), item(3, "video"), SlideshowOptions(order = "reverse"), 500)
        assertEquals(listOf("3", "4", "5"), a.items.map { it.id })
        assertEquals(listOf("3", "2", "1"), b.items.map { it.id })
    }

    @Test fun cancellationAndNetworkFailureDoNotSkipContinuation() = runBlocking {
        var attempts = 0
        val sequence = SlideSequence(listOf(item(1))) {
            when (++attempts) { 1 -> throw CancellationException("cancelled"); 2 -> throw IOException("offline"); else -> listOf(item(2)) to false }
        }
        try { sequence.more(); fail() } catch (_: CancellationException) { }
        try { sequence.more(); fail() } catch (_: IOException) { }
        assertEquals(listOf("1"), sequence.items.map { it.id }); assertFalse(sequence.exhausted)
        assertEquals("2", sequence.more().first.single().id); assertTrue(sequence.exhausted)
    }

    @Test fun duplicatePageWithContinuationDoesNotEndPlaylist() = runBlocking {
        var calls = 0
        val feed = ViewerFeed(listOf(item(1)), onLoadError = {}) { _, _ ->
            if (calls++ == 0) listOf(item(1)) to "next" else listOf(item(2)) to null
        }
        assertFalse(feed.loadMore()); assertFalse(feed.exhausted)
        assertTrue(feed.loadMore()); assertTrue(feed.exhausted)
    }

    @Test fun switchingModeDoesNotLoseAnAlreadyCompletedPrefetch() = runBlocking {
        var next = 2
        val sequence = SlideSequence(listOf(item(1))) { listOf(item(next++)) to (next <= 4) }
        val source = SlideSource(SlideScope("photo"), sequence = sequence)
        val feed = createSlideshow(source, Library(), item(1), SlideshowOptions(), 500)
        sequence.more() // the outgoing normal-viewer prefetch finished after the queue snapshot
        assertTrue(feed.loadMore())
        assertEquals(listOf("1", "2"), feed.items.map { it.id })
        feed.loadMore(); assertEquals(listOf("1", "2", "3"), feed.items.map { it.id })
    }

    @Test fun menuAndGesturesHoldPlaybackWithoutUndoingManualPause() {
        val state = SlideshowPlayback(); state.start(SlideshowOptions())
        assertTrue(state.canAdvance())
        assertFalse(state.canAdvance(menu = true)); assertFalse(state.canAdvance(touching = true)); assertFalse(state.canAdvance(zoomed = true))
        assertTrue(state.playing)
        state.pause(); assertFalse(state.canAdvance()); assertFalse(state.canAdvance(menu = true))
        state.resume(); assertTrue(state.canAdvance())
        state.end(); assertFalse(state.canAdvance()); state.resume(); assertFalse(state.canAdvance())
        state.stop(); assertFalse(state.active)
    }

    @Test fun skipFailureResumesOnlyWhenPlaybackWasRunning() {
        val state = SlideshowPlayback(); state.start(SlideshowOptions()); state.fail("bad media")
        assertFalse(state.playing); state.ready(); assertTrue(state.playing)
        state.pause(); state.fail("bad media"); state.ready(); assertFalse(state.playing)
    }
}
