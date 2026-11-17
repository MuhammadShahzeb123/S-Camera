package com.shahzeb.scamera;

/** Dimensions for a gallery thumbnail, independent of the view's layout lifecycle. */
final class ThumbnailSize {
    private ThumbnailSize() {
    }

    static int[] fit(int width, int height, int viewWidth) {
        // A GONE legacy button is never measured by the replacement camera UI.
        int targetWidth = viewWidth > 0 ? viewWidth : 220;
        if( width <= targetWidth )
            return new int[]{width, height};
        float scale = (float)targetWidth / width;
        return new int[]{targetWidth, Math.max(1, Math.round(scale * height))};
    }
}
