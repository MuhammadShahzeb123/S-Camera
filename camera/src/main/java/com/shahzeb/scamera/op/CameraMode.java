package com.shahzeb.scamera.op;

import com.shahzeb.scamera.R;

/** All camera modes offered by the OnePlus style UI.
 *  Each mode is served either by the classic Open Camera engine (MainActivity) or by the
 *  Studio engine (StudioActivity, GPU pipeline with multi-camera support).
 */
public enum CameraMode {
    NIGHT("NIGHT", "Night", Engine.CLASSIC, false, R.drawable.op_ic_bedtime),
    VIDEO("VIDEO", "Video", Engine.CLASSIC, true, R.drawable.op_ic_videocam),
    PHOTO("PHOTO", "Photo", Engine.CLASSIC, false, R.drawable.op_ic_photo_camera),
    PORTRAIT("PORTRAIT", "Portrait", Engine.STUDIO, false, R.drawable.op_ic_frame_person),
    PRO("PRO", "Pro", Engine.CLASSIC, false, R.drawable.op_ic_tune),
    MULTI_CAM("MULTI-CAM", "Multi-Cam", Engine.STUDIO, true, R.drawable.op_ic_grid_view),
    // modes listed under "More":
    PRO_VIDEO("PRO VIDEO", "Pro Video", Engine.CLASSIC, true, R.drawable.op_ic_movie_filter),
    FILM("CINEMATIC", "Cinematic Video", Engine.STUDIO, true, R.drawable.op_ic_theaters),
    DUAL_VIEW("DUAL-VIEW", "Dual-View", Engine.STUDIO, true, R.drawable.op_ic_splitscreen),
    SLOW_MO("SLOW-MO", "Slow-mo", Engine.CLASSIC, true, R.drawable.op_ic_slow_motion_video),
    TIME_LAPSE("TIME-LAPSE", "Time-lapse", Engine.CLASSIC, true, R.drawable.op_ic_timelapse),
    LONG_EXPOSURE("LONG EXPOSURE", "Long Exposure", Engine.STUDIO, false, R.drawable.op_ic_shutter_speed),
    PANORAMA("PANO", "Panorama", Engine.CLASSIC, false, R.drawable.op_ic_panorama);

    public enum Engine {
        CLASSIC,
        STUDIO
    }

    public final String carouselLabel;
    public final String title;
    public final Engine engine;
    public final boolean isVideo;
    public final int iconRes;

    CameraMode(String carouselLabel, String title, Engine engine, boolean isVideo, int iconRes) {
        this.carouselLabel = carouselLabel;
        this.title = title;
        this.engine = engine;
        this.isVideo = isVideo;
        this.iconRes = iconRes;
    }

    /** Modes shown directly in the swipe carousel, in order. The final carousel entry is "MORE". */
    public static final CameraMode [] CAROUSEL = {PHOTO, VIDEO, PRO_VIDEO, FILM};

    /** Modes shown in the "More" sheet. */
    public static final CameraMode [] MORE = {NIGHT, PORTRAIT, PRO, DUAL_VIEW, SLOW_MO, TIME_LAPSE, LONG_EXPOSURE, PANORAMA, MULTI_CAM};

    public boolean usesOpticalVideo() {
        return this == VIDEO || this == PRO_VIDEO || this == FILM;
    }

    public static int carouselIndexOf(CameraMode mode) {
        for(int i=0;i<CAROUSEL.length;i++) {
            if( CAROUSEL[i] == mode )
                return i;
        }
        return -1;
    }

    public static CameraMode fromName(String name, CameraMode def) {
        if( name == null )
            return def;
        try {
            return CameraMode.valueOf(name);
        }
        catch(IllegalArgumentException e) {
            return def;
        }
    }
}
