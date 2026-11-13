package com.aly.sahltv

/** One source of truth for tile dimensions across Favorites, Recent, combined and All apps. */
internal data class HomeTileDimensions(val widthDp: Int, val heightDp: Int)

internal fun homeTileDimensions(size: String): HomeTileDimensions = when (size) {
    "large" -> HomeTileDimensions(200, 112)
    "normal" -> HomeTileDimensions(160, 90)
    else -> HomeTileDimensions(130, 73)
}

/**
 * Visible app sections in top-to-bottom order. Empty Favorites never reserve a row,
 * so Recent becomes the first app row when there are no Favorites.
 */
internal fun visibleHomeSections(
    favoritesCount: Int,
    recentCount: Int,
    showFavorites: Boolean,
    showRecent: Boolean,
    combined: Boolean
): List<String> {
    val fav = showFavorites && favoritesCount > 0
    val rec = showRecent && recentCount > 0
    return if (combined) {
        if (fav || rec) listOf("combined") else emptyList()
    } else {
        buildList {
            if (fav) add("fav")
            if (rec) add("rec")
        }
    }
}

/** Conservative selectable Recent limits; invalid imported settings fall back to six. */
internal fun homeRecentLimit(value: String): Int = when (value) {
    "3" -> 3
    "8" -> 8
    else -> 6
}

/** Horizontal tile margins in dp. Does not change actual tile or dashboard dimensions. */
internal fun homeTileGap(value: String): Int = when (value) {
    "tight" -> 2
    "spacious" -> 12
    else -> 6
}
