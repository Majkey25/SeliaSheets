package com.majkeylab.seliadocs.pdf;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

interface IPdfRenderService {
    Bundle inspect(in ParcelFileDescriptor pdf);
    Bundle renderPage(
        in ParcelFileDescriptor pdf,
        int pageIndex,
        int width,
        int height,
        in ParcelFileDescriptor output
    );
    Bundle selectText(
        in ParcelFileDescriptor pdf,
        int pageIndex,
        float startX,
        float startY,
        float endX,
        float endY
    );
    Bundle searchText(in ParcelFileDescriptor pdf, int pageIndex, String query);
}
