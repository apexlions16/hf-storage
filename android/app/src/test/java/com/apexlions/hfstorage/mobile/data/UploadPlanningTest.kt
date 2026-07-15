package com.apexlions.hfstorage.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Test

class UploadPlanningTest {
    @Test
    fun oneHundredFilesUseOneCommit() {
        assertEquals(1, planCommitBatches((1..100).toList()).size)
    }

    @Test
    fun oneHundredAndOneFilesUseTwoCommits() {
        val batches = planCommitBatches((1..101).toList())
        assertEquals(listOf(100, 1), batches.map { it.size })
    }

    @Test
    fun remotePathIsAlwaysPosix() {
        assertEquals("music/album/song.flac", normalizeRemotePath("music\\album", "song.flac"))
    }
}
