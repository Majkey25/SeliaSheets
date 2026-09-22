package com.majkeylab.seliadocs.editor

import com.majkeylab.seliadocs.data.PageTextMatch
import com.majkeylab.seliadocs.pdf.PdfTextSelection

internal data class NotebookSearchResult(val page: PageTextMatch, val pdfMatch: PdfTextSelection? = null)

internal data class PdfSearchHighlight(val pageId: String, val selection: PdfTextSelection)
