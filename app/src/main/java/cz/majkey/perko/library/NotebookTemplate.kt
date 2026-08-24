package cz.majkey.perko.library

import cz.majkey.perko.data.CoverColor
import cz.majkey.perko.data.CoverPattern
import cz.majkey.perko.data.PageOrientation
import cz.majkey.perko.data.PaperTemplate

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
