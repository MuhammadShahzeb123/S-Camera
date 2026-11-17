package net.sourceforge.opencamera;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;

public class ThumbnailSizeTest {
    @Test
    public void hiddenLegacyGalleryStillProducesAUsableVideoThumbnail() {
        // The replacement UI leaves the legacy gallery in a GONE container: measured width is 0.
        assertArrayEquals(new int[]{220, 124}, ThumbnailSize.fit(1920, 1080, 0));
    }

    @Test
    public void visibleGalleryUsesItsWidthAndPreservesAspect() {
        assertArrayEquals(new int[]{120, 68}, ThumbnailSize.fit(1920, 1080, 120));
        assertArrayEquals(new int[]{120, 213}, ThumbnailSize.fit(1080, 1920, 120));
    }

    @Test
    public void smallThumbnailsAreNotUpscaled() {
        assertArrayEquals(new int[]{64, 36}, ThumbnailSize.fit(64, 36, 0));
        assertArrayEquals(new int[]{64, 36}, ThumbnailSize.fit(64, 36, 120));
    }

    @Test
    public void narrowAspectNeverRoundsADimensionToZero() {
        assertArrayEquals(new int[]{1, 1}, ThumbnailSize.fit(4096, 1, 1));
    }
}
