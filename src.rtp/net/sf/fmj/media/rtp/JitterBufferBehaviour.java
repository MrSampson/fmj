package net.sf.fmj.media.rtp;

import javax.media.*;
import javax.media.protocol.*;

public interface JitterBufferBehaviour
{
    void preAdd();
    void handleFull();
    void read(Buffer buffer);
    void start();
    void stop();
    void setTransferHandler(BufferTransferHandler xiBuffertransferhandler);
}
