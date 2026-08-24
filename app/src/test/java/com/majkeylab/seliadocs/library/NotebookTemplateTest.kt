package com.majkeylab.seliadocs.library

import com.majkeylab.seliadocs.data.CoverColor
import com.majkeylab.seliadocs.data.PageOrientation
import com.majkeylab.seliadocs.data.PaperTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookTemplateTest {
    @Test
    fun templatesMapToRealNotebookFields() {
        assertEquals(PaperTemplate.RULED, NotebookTemplate.RULED_NOTES.paper)
        assertEquals(CoverColor.SAGE, NotebookTemplate.GRID_NOTEBOOK.coverColor)
        assertEquals(PaperTemplate.DOT, NotebookTemplate.DOTTED_JOURNAL.paper)
        assertEquals(PageOrientation.LANDSCAPE, NotebookTemplate.BLANK_SKETCHBOOK.orientation)
    }

    @Test
    fun customDetectionUsesAllMappedFields() {
        val template = NotebookTemplate.RULED_NOTES

        assertTrue(
            template.matches(
                template.coverColor,
                template.coverPattern,
                template.paper,
                template.orientation,
            ),
        )
        assertFalse(
            template.matches(
                template.coverColor,
                template.coverPattern,
                PaperTemplate.GRID,
                template.orientation,
            ),
        )
    }
}
