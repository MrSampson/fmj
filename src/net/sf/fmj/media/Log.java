package net.sf.fmj.media;

import java.util.*;
import java.util.logging.*;

/**
 * A public static class to generate and write to fmj.log.
 */
public class Log
{
    public static boolean isEnabled = false; /* The default JMF value is false. */
    private static int indent = 0;

    /*
     * Set of objects that have been seen by the logger.  This must be
     * synchronized as multiple threads will write logs.
     */
    static Set<Integer> seenObjects =
        Collections.synchronizedSet(new HashSet<Integer>());

    /**
     * The Logger instance to be used.
     */
    private static Logger logger = Logger.getLogger(Log.class.getName());

    static
    {
        // Check the registry file to see if logging is turned on.
        Object llog = com.sun.media.util.Registry.get("allowLogging");

        if ((llog != null) && (llog instanceof Boolean))
            isEnabled = ((Boolean) llog).booleanValue();

        if (isEnabled)
            writeHeader();
    }

    public static synchronized void comment(Object str)
    {
        if (isEnabled && logger.isLoggable(Level.FINE))
            logger.fine((str!=null ? str.toString() : "null"));
    }

    public static synchronized void info(Object str)
    {
        if (isEnabled && logger.isLoggable(Level.INFO))
            logger.info((str!=null ? str.toString() : "null"));
    }

    public static synchronized void decrIndent()
    {
        indent--;
    }

    public static synchronized void dumpStack(Throwable e)
    {
        if (isEnabled && logger.isLoggable(Level.INFO))
        {
            StringBuffer buf = new StringBuffer(e.toString() + "\n");
            for(StackTraceElement s : e.getStackTrace())
            {
                buf.append(s.toString());
                buf.append("\n");
            }

            logger.info(buf.toString());
        }
    }

    static HashMap<Object, int[]> packetTracker = new HashMap<Object, int[]>();
    static int LOG_FIRST_N_READS = 10;
    static int LOG_EACH_NTH_READ_1 = 50;
    static int LOG_PHASE_1_TO_N_READS = 500;
    static int LOG_EACH_NTH_READ_2 = 500;
    public static synchronized void logRead(Object obj)
    {
        logReadBytes(obj, 0, false);
    }
    public static synchronized void logReadBytes(Object obj, int nBytes)
    {
        logReadBytes(obj, nBytes, true);
    }
    
    private static synchronized void logReadBytes(Object obj, int nBytes,
                                                               boolean logBytes)
    {
        if (isEnabled && logger.isLoggable(Level.INFO))
        {
            int[] thisData = packetTracker.get(obj);
            if (thisData == null)
            {
                thisData = new int[2];
                thisData[0] = thisData[1] = 0;
            }
            
            int nCalled = thisData[0] + 1;
            int totalBytes = thisData[1] + nBytes;
            thisData[0] = nCalled;
            thisData[1] = totalBytes;
            packetTracker.put(obj, thisData);
            
            // Only log: the first 10 packets; then every 50th packet up to
            // 500; and then every 500th thereafter.
            if ((nCalled <= LOG_FIRST_N_READS) ||
                ((nCalled <= LOG_PHASE_1_TO_N_READS) &&
                 ((nCalled % LOG_EACH_NTH_READ_1) == 0)) ||
                ((nCalled % LOG_EACH_NTH_READ_2) == 0))
            {
                String byteStr;
                if (logBytes)
                    byteStr = "(" + totalBytes + " bytes total) "; 
                else
                    byteStr = "";
                logger.info("logReadBytes called " + nCalled + " times " +
                                                byteStr + "from " + obj + "\n");
            }
        }
    }
    
    public static synchronized void error(Object str)
    {
        if (isEnabled && logger.isLoggable(Level.SEVERE))
        {
            logger.severe((str!=null ? str.toString() : "null"));
        } else
        {
            System.err.println(str);
        }
    }

    public static int getIndent()
    {
        return indent;
    }

    public static synchronized void incrIndent()
    {
        indent++;
    }

    public static synchronized void profile(Object str)
    {
        if (isEnabled && logger.isLoggable(Level.FINER))
            logger.finer((str!=null ? str.toString() : "null"));
    }

    public static synchronized void setIndent(int i)
    {
        indent = i;
    }

    public static synchronized void warning(Object str)
    {
        if (isEnabled && logger.isLoggable(Level.WARNING))
        {
                logger.warning((str!=null ? str.toString() : "null"));
        }
    }

    public static synchronized void write(Object str)
    {
        if (isEnabled && logger.isLoggable(Level.FINE))
        {
            StringBuilder sb = new StringBuilder();
            for (int i = indent; i > 0; i--)
                sb.append("    ");
            sb.append(str!=null ? str.toString() : "null");
            logger.fine(sb.toString());
        }
    }

    private static synchronized void writeHeader()
    {
        write("#\n# FMJ\n#\n");

        String os = null, osver = null, osarch = null;
        String java = null, jver = null;
        try
        {
            os = System.getProperty("os.name");
            osarch = System.getProperty("os.arch");
            osver = System.getProperty("os.version");
            java = System.getProperty("java.vendor");
            jver = System.getProperty("java.version");
        } catch (Throwable e)
        {
            // Can't get the info. No big deal.
            return;
        }

        if (os != null)
            comment("Platform: " + os + ", " + osarch + ", " + osver);
        if (java != null)
            comment("Java VM: " + java + ", " + jver);
        write("");
    }

    public static void objectCreated(Object object,
            String description)
    {
        seenObjects.add(object.hashCode());

        if (isEnabled && logger.isLoggable(Level.FINE))
            logger.fine("CREATE " + object.hashCode() + " " + description);
    }

    public static void createLink(Object source, Object destination, String description)
    {
        if (source != null && ! seenObjects.contains(source.hashCode()))
        {
            logger.fine("LINK missing CREATE for source " + source.toString());
        }

        if (destination != null && ! seenObjects.contains(destination.hashCode()))
        {
            logger.fine("LINK missing CREATE for destination " + destination.toString());
        }

        if (isEnabled && logger.isLoggable(Level.FINE))
            logger.fine("LINK " + ((source == null) ? "null" :source.hashCode()) + " " + ((destination == null) ? "null" : destination.hashCode()) + " " + description);
    }

    public static void annotate(Object source, String description)
    {
        if (isEnabled && logger.isLoggable(Level.FINE))
            logger.fine("ANNOTATE " + source.hashCode() + " " + description);
    }
}
