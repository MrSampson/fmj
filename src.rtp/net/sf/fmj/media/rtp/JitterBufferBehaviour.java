// JitterBufferBehaviour.java
// (C) COPYRIGHT METASWITCH NETWORKS 2013
package net.sf.fmj.media.rtp;

import javax.media.*;
import javax.media.protocol.*;

public interface JitterBufferBehaviour
{

    void preAdd(Buffer xiBuffer);

    void handleFull();

    void read(Buffer xiBuffer);

    void start();

    void stop();

    void setTransferHandler(BufferTransferHandler xiBuffertransferhandler);

}
