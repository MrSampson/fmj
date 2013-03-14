// RTPSourceStreamTest.java
// (C) COPYRIGHT METASWITCH NETWORKS 2013
package net.sf.fmj.media.rtp;

import static org.junit.Assert.assertEquals;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import javax.media.*;
import javax.media.protocol.*;

import org.junit.*;

public class RTPSourceStreamTest
{
    public class EOMAwareHandlerWithCounter implements BufferTransferHandler
    {
        final AtomicInteger packetsRecieved = new AtomicInteger(0);
        final CountDownLatch startSignal = new CountDownLatch(1);
        long lastSeqNo = -1;

            public void transferData(PushBufferStream xiStream)
            {
                Buffer buffer = new Buffer();
                    try
                    {
                        xiStream.read(buffer);
                    }
                    catch (IOException e)
                    {
                        e.printStackTrace();
                    }

                if (buffer.isEOM())
                {
                    startSignal.countDown();
                }
                else
                {
                    if (lastSeqNo != -1)
                    {
                      assertEquals("Sequence number didn't increase by one", (lastSeqNo + 1) % maxSeqNum, buffer.getSequenceNumber());
                    }

                    lastSeqNo = buffer.getSequenceNumber();
                    packetsRecieved.incrementAndGet();
                }
            }
    }

    public class HandlerThatDoesNotRead implements BufferTransferHandler
    {
        final AtomicInteger packetsRecieved = new AtomicInteger(0);

            public void transferData(PushBufferStream xiStream)
            {
                    packetsRecieved.incrementAndGet();
            }
    }


    static RTPSourceStream stream = null;
    static final Buffer eomBuffer = new Buffer();
    private static final int maxSeqNum = 65536;

    @BeforeClass
    public static void setUpBeforeClass() throws Exception
    {

    }

    @AfterClass
    public static void tearDownAfterClass() throws Exception
    {
    }

    @Before
    public void setUp() throws Exception
    {
        eomBuffer.setEOM(true);
        stream = new RTPSourceStream(new TestDataSource());
    }

    @After
    public void tearDown() throws Exception
    {
    }

    @Test
    public void testSeqNumWrapping() throws InterruptedException
    {
        final EOMAwareHandlerWithCounter handler = new EOMAwareHandlerWithCounter();
        stream.setTransferHandler(handler);
        stream.start();

        // Start the seq num at 65000 then send in 1000 packets
        int seqStart = 65000;
        int packetsToSend = 1000;

        for (int ii = seqStart; ii < seqStart + packetsToSend; ii++)
        {
            while (stream.q.isFull())
            {
                //Avoid adding packets to the queue if it's full.
                Thread.sleep(1);
            }

            Buffer buffer = new Buffer();
            buffer.setSequenceNumber(ii % maxSeqNum);
            stream.add(buffer);
        }

        eomBuffer.setSequenceNumber((seqStart + packetsToSend) % maxSeqNum);
        stream.add(eomBuffer);

        handler.startSignal.await(1, TimeUnit.SECONDS);

        assertEquals("Not all packets made it through",packetsToSend, handler.packetsRecieved.get());
        assertEquals("The stream was reset", 0, stream.getTimesReset());
    }

    @Test
    public void testPacketsAreDiscardedWhenFull()
    {
        final HandlerThatDoesNotRead handler = new HandlerThatDoesNotRead();
        stream.setTransferHandler(handler);
        stream.start();

        int packetsToSend = 100;

        //Send in some packets
         for (int ii = 0; ii < packetsToSend; ii++)
        {
            Buffer buffer = new Buffer();
            buffer.setSequenceNumber(ii);
            stream.add(buffer);
        }

         //Silly calculation because we're clearing the whole jitter buffer
        assertEquals("Not enough packets were dropped",
                     packetsToSend -
                             (packetsToSend % stream.maxJitterQueueSize),
                     stream.getDiscardedReset()); //TODO Should be xxxFull

        assertEquals("Didn't reset enough times",
                     packetsToSend / stream.maxJitterQueueSize,
                     stream.getTimesReset());
    }

    @Test
    public void testOutOfOrderPackets() throws InterruptedException
    {
        final EOMAwareHandlerWithCounter handler = new EOMAwareHandlerWithCounter();
        stream.setTransferHandler(handler);

        // Insert few enough packets that we will never get full.
        // Wrap over the seqnum boundary.
        int seqStart = maxSeqNum - stream.maxJitterQueueSize/2;
        int packetsToSend = stream.maxJitterQueueSize - 1;

        List<Buffer> buffers = new ArrayList<Buffer>();

        for (int ii = seqStart; ii < seqStart + packetsToSend; ii++)
        {
            Buffer buffer = new Buffer();
            buffer.setSequenceNumber(ii % maxSeqNum);
            buffers.add(buffer);
        }

        Collections.shuffle(buffers);

        // Now send the buffers
        for (Buffer aBuf : buffers)
        {
            stream.add(aBuf);
        }

        //We're ready to start reading packets now.
        stream.start();

        eomBuffer.setSequenceNumber((seqStart + packetsToSend) % maxSeqNum);
        stream.add(eomBuffer);

        handler.startSignal.await(1, TimeUnit.SECONDS);

        assertEquals("Not all packets made it through",packetsToSend, handler.packetsRecieved.get());
        assertEquals("The stream was reset", 0, stream.getTimesReset());
    }

    //Tests to add
    // Duplicate packet detection
}
