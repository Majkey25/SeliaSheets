package com.majkeylab.seliadocs.library

import com.majkeylab.seliadocs.data.CoverColor
import com.majkeylab.seliadocs.data.CoverPattern
import com.majkeylab.seliadocs.data.PageOrientation
import com.majkeylab.seliadocs.data.PaperTemplate

internal enum class NotebookTemplate(
    val coverColor: CoverColor,
    val coverPattern: CoverPattern,
    val paper: PaperTemplate,
    val orientation: PageOrientation,
) {
    RULED_NOTES(
        CoverColor.PERIWINKLE,
        CoverPattern.SOLID,
        PaperTemplate.RULED,
        PageOrientation.PORTRAIT,
    ),
    GRID_NOTEBOOK(
        CoverColor.SAGE,
        CoverPattern.GRID,
        PaperTemplate.GRID,
        PageOrientation.PORTRAIT,
    ),
    DOTTED_JOURNAL(
        CoverColor.SAND,
        CoverPattern.CORNERS,
        PaperTemplate.DOT,
        PageOrientation.PORTRAIT,
    ),
    BLANK_SKETCHBOOK(
        CoverColor.SALMON,
        CoverPattern.BAND,
        PaperTemplate.BLANK,
        PageOrientation.LANDSCAPE,
    ),
    ;

    fun matches(
        coverColor: CoverColor,
        coverPattern: CoverPattern,
        paper: PaperTemplate,
        orientation: PageOrientation,
    ): Boolean =
        this.coverColor == coverColor &&
            this.coverPattern == coverPattern &&
            this.paper == paper &&
            this.orientation == orientation
}
