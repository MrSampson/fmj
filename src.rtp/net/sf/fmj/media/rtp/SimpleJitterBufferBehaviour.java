// SimpleJitterBufferBehaviour.java
// (C) COPYRIGHT METASWITCH NETWORKS 2013
package net.sf.fmj.media.rtp;

import javax.media.*;
import javax.media.protocol.*;

public class SimpleJitterBufferBehaviour implements JitterBufferBehaviour
{

    private RTPSourceStream stream;
    private JitterBuffer q;

    public SimpleJitterBufferBehaviour(JitterBuffer q, RTPSourceStream stream)
    {
         this.q = q;
         this.stream = stream;
    }

    @Override
    public void preAdd()
    {
        //Do Nothing
    }

    @Override
    public void handleFull()
    {
        // TODO Auto-generated method stub
    }

    @Override
    public void read(Buffer buffer)
    {
        // TODO Auto-generated method stub
         Buffer bufferToCopyFrom = q.get();
        buffer.copy(bufferToCopyFrom);

    }

    @Override
    public void start()
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void stop()
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void setTransferHandler(BufferTransferHandler xiBuffertransferhandler)
    {

    }

}
