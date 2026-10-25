package com.localtg.data

data class SlideshowOptions(val imageSeconds: Int = 5, val order: String = "forward")

private fun List<Item>.slideOrder(options: SlideshowOptions) =
    if (options.order == "shuffle") shuffled() else this

/** Read in pages, never eagerly fetch an entire group or library. */
suspend fun groupSlideshow(api: Api, dialog: String, sort: String, direction: String, options: SlideshowOptions): ViewerFeed {
    val dir = if (options.order == "reverse") { if (direction == "asc") "desc" else "asc" } else direction
    val first = api.history(dialog, sort, dir, "photo,video,gif", null, 60)
    var cursor = first.nextCursor
    return ViewerFeed(first.items.slideOrder(options), slideshow = options) { _, _ ->
        val next = cursor
        if (next == null) emptyList<Item>() to null else {
            val page = api.history(dialog, sort, dir, "photo,video,gif", next, 60)
            cursor = page.nextCursor
            page.items.slideOrder(options) to cursor
        }
    }
}

suspend fun randomSlideshow(api: Api, roots: String?, seed: Long, options: SlideshowOptions): ViewerFeed {
    var batch = 0
    val first = api.random("photo,video,gif", 60, roots, seed, batch++)
    return ViewerFeed(first.items.slideOrder(options), slideshow = options) { _, _ ->
        val page = api.random("photo,video,gif", 60, roots, seed, batch)
        batch++ // Advance only after a successful fetch, so retry never skips a page.
        page.items.slideOrder(options) to if (page.items.isEmpty()) null else "more"
    }
}
