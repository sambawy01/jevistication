package dev.loupe.kit.watchers

import dev.loupe.sources.DesktopPlatformExtractors
import dev.loupe.sources.common.PlatformExtractors
import java.time.ZoneOffset

/** PDFBox and metadata-extractor: the desktop's readers. */
internal actual fun sampleReaders(): PlatformExtractors = DesktopPlatformExtractors(ZoneOffset.UTC)
