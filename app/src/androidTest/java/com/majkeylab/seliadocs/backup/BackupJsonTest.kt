package com.majkeylab.seliadocs.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.StringReader
import java.io.StringWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupJsonTest {
    @Test
    fun manifestAndRecordsRoundTripWithoutLosingFields() {
        val manifest =
            BackupManifest(
                formatVersion = 3,
                appVersion = "0.1.0-beta.1",
                exportedAt = 42L,
                notebookCount = 1,
                pageCount = 1,
                assetCount = 1,
                featureFlags = setOf("ink", "elements", "page-text", "pdf-sources"),
            )
        val notebook =
            BackupNotebook(
                id = "notebook",
                title = "Physics",
                coverColor = "PERIWINKLE",
                coverPattern = "GRID",
                defaultPaper = "RULED",
                orientation = "PORTRAIT",
                fingerDrawing = true,
                favorite = false,
                createdAt = 10L,
                updatedAt = 20L,
                trashedAt = null,
            )
        val page =
            BackupPage(
                "page",
                "notebook",
                0,
                "RULED",
                595,
                842,
                chapterId = "chapter",
                title = "Lecture 1",
                bookmarked = true,
                createdAt = 10,
                updatedAt = 20,
                pdfSourceId = "pdf-source",
                pdfPageIndex = 0,
            )
        val chapter = BackupChapter("chapter", "notebook", "Mechanics", 0xFF3156D9.toInt(), 0)
        val pdfSource =
            BackupPdfSource(
                "pdf-source",
                "notebook",
                "slides.pdf",
                "Slides.pdf",
                1,
                123,
                "0".repeat(64),
                10,
            )
        val stroke =
            BackupStroke(
                id = "stroke",
                pageId = "page",
                zIndex = 0,
                brushKind = "PRESSURE_PEN",
                colorArgb = 0xff000000.toInt(),
                size = 3f,
                epsilon = 0.1f,
                inputs = byteArrayOf(1, 2, 3),
            )
        val element =
            BackupElement(
                id = "element",
                pageId = "page",
                zIndex = 1,
                kind = "TEXT",
                x = 12f,
                y = 24f,
                width = 120f,
                height = 48f,
                rotation = 5f,
                text = "Velocity",
                assetId = null,
                shapeKind = null,
                expression = null,
                resultText = null,
            )
        val block =
            BackupBlock("block", "page", 0, "PARAGRAPH", "Typed notes", false, 0, "START", null)

        val manifestOutput = StringWriter()
        BackupJson.writeManifest(manifestOutput, manifest)
        val recordOutput = StringWriter()
        listOf(notebook, chapter, pdfSource, page, stroke, element, block).forEach { BackupJson.writeRecord(recordOutput, it) }
        val decoded = mutableListOf<BackupRecord>()
        BackupJson.readRecords(StringReader(recordOutput.toString()), decoded::add)

        assertEquals(manifest, BackupJson.readManifest(StringReader(manifestOutput.toString())))
        assertEquals(notebook, decoded[0])
        assertEquals(chapter, decoded[1])
        assertEquals(pdfSource, decoded[2])
        assertEquals(page, decoded[3])
        assertArrayEquals(stroke.inputs, (decoded[4] as BackupStroke).inputs)
        assertEquals(element, decoded[5])
        assertEquals(block, decoded[6])
    }

    @Test
    fun unsupportedVersionReturnsTypedFailure() {
        assertThrows(BackupFailure.UnsupportedVersion::class.java) {
            BackupJson.readManifest(
                StringReader("""{"formatVersion":4,"appVersion":"x","exportedAt":1}"""),
            )
        }
    }

    @Test
    fun unknownRecordKindReturnsTypedFailure() {
        assertThrows(BackupFailure.UnknownRecordKind::class.java) {
            BackupJson.readRecords(StringReader("""{"kind":"future"}""")) {}
        }
    }

    @Test
    fun invalidStoredEnumsAreRejected() {
        assertThrows(BackupFailure.Malformed::class.java) {
            BackupJson.readRecords(
                StringReader(
                    """{"kind":"stroke","id":"s","pageId":"p","zIndex":0,"brushKind":"PEN","colorArgb":-16777216,"size":3.0,"epsilon":0.1,"inputs":""}""",
                ),
            ) {}
        }
    }

    @Test
    fun nonFiniteCoordinateReturnsTypedFailure() {
        assertThrows(BackupFailure.InvalidNumber::class.java) {
            BackupJson.readRecords(
                StringReader(
                    """{"kind":"element","id":"e","pageId":"p","zIndex":0,"elementKind":"TEXT","x":1e400,"y":0,"width":1,"height":1,"rotation":0}""",
                ),
            ) {}
        }
    }

    @Test
    fun oversizedTextReturnsTypedFailure() {
        assertThrows(BackupFailure.LimitExceeded::class.java) {
            val text = "x".repeat(1_048_577)
            BackupJson.readRecords(
                StringReader(
                    """{"kind":"element","id":"e","pageId":"p","zIndex":0,"elementKind":"TEXT","x":0,"y":0,"width":1,"height":1,"rotation":0,"text":"$text"}""",
                ),
            ) {}
        }
    }
}
