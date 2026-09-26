package com.craftworks.music.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The default nav list used to be copy-pasted into five places, and the icon
 * mapping into four. They had already drifted (the TV copy disabled
 * `songs_screen` while the others didn't). These tests pin the single source.
 */
class NavigationItemsTest {

    @Test
    fun `default list contains the six expected routes in order`() {
        assertEquals(
            listOf(
                NavItems.HOME,
                NavItems.ALBUMS,
                NavItems.SONGS,
                NavItems.ARTISTS,
                NavItems.RADIOS,
                NavItems.PLAYLISTS
            ),
            NavItems.default.map { it.screenRoute }
        )
    }

    @Test
    fun `default list enables every item`() {
        assertEquals(6, NavItems.default.size)
        assertEquals(6, NavItems.default.count { it.enabled })
    }

    @Test
    fun `default list has no duplicate routes`() {
        assertEquals(
            NavItems.default.size,
            NavItems.default.map { it.screenRoute }.toSet().size
        )
    }

    @Test
    fun `every default item has a distinct icon`() {
        val icons = NavItems.default.map { it.icon }
        assertEquals(icons.size, icons.toSet().size)
    }

    @Test
    fun `iconFor resolves every default route`() {
        NavItems.default.forEach { item ->
            assertEquals(
                "route ${item.screenRoute} fell back to the placeholder",
                item.icon,
                NavItems.iconFor(item.screenRoute)
            )
        }
    }
}
