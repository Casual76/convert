package com.p2r3.convert

import com.p2r3.convert.data.preset.DefaultPresetRepository
import com.p2r3.convert.model.legacyFormatId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultPresetRepositoryTest {
    @Test
    fun `featured presets stay available for home screen`() = runBlocking {
        val repository = DefaultPresetRepository()
        val featured = repository.featuredPresets.first()

        assertTrue(featured.isNotEmpty())
        assertTrue(featured.any { it.category == "Images" })
        assertEquals(legacyFormatId("png", "image/png"), featured.first { it.id == "png-to-jpeg" }.sourceFormatId)
        assertEquals(legacyFormatId("jpeg", "image/jpeg"), featured.first { it.id == "png-to-jpeg" }.targetFormatId)
    }
}
