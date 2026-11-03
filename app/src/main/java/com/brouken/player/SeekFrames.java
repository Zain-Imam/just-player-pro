package com.brouken.player;

// pictures for the seek bubble: read from the film itself, or a site's own storyboard
interface SeekFrames {

    boolean failed();

    void request(long positionMs, PreviewGrabber.Callback callback);

    // the drag is over
    void cancel();

    // nobody is dragging for a while
    void rest();

    void release();
}
