(async () => {
window.presentationResult = { state: 'loading' };
try {
  const { renderSlide, parseZipLazyMedia, buildPresentation } = await import('./renderer.js').catch(() => {
    throw new Error('The slide renderer could not start. Update Android System WebView or import a PDF.');
  });
  const response = await fetch('source.pptx');
  if (!response.ok) throw new Error('Presentation source unavailable');
  const files = await parseZipLazyMedia(await response.arrayBuffer(), {
    maxEntries: 2048,
    maxEntryUncompressedBytes: 16 * 1024 * 1024,
    maxTotalUncompressedBytes: 128 * 1024 * 1024,
    maxMediaBytes: 128 * 1024 * 1024,
    maxConcurrency: 2,
  });
  const model = buildPresentation(files, { lazySlides: true });
  let failed = false;
  const chartInstances = new Set();
  if (model.slides.length < 1 || model.slides.length > 100) throw new Error('Unsupported slide count');
  let handle;
  window.renderPresentationSlide = async index => {
    window.presentationResult = { state: 'loading' };
    try {
      if (handle) handle.dispose();
      document.body.textContent = '';
      const container = document.createElement('section');
      container.className = 'slide';
      container.style.width = `${model.width}px`;
      container.style.height = `${model.height}px`;
      document.body.append(container);
      handle = renderSlide(model, model.slides[index], {
        pdfjs: false, embeddedFontLimits: { maxFaces: 0 }, chartInstances,
        onNodeError: () => { failed = true; },
      });
      container.append(handle.element);
      await handle.ready;
      if (failed) throw new Error('Slide contains unsupported content. Export it as PDF first.');
      for (const chart of chartInstances) {
        chart.setOption({
          animation: false, animationDuration: 0, animationDurationUpdate: 0,
          series: chart.getOption().series.map(() => ({ animation: false, animationDuration: 0, animationDurationUpdate: 0 })),
        }, { lazyUpdate: false });
        chart.getZr().flush();
      }
      await document.fonts.ready;
      await Promise.all(Array.from(document.images, image => image.decode()));
      window.presentationResult = { state: 'ready', index };
    } catch (error) {
      window.presentationResult = { state: 'error', message: String(error.message || error).slice(0, 300) };
    }
  };
  window.presentationResult = { state: 'loaded', count: model.slides.length, width: model.width, height: model.height };
} catch (error) {
  window.presentationResult = { state: 'error', message: String(error.message || error).slice(0, 300) };
}
})();
