// ConfigUtils.java
// (C) COPYRIGHT METASWITCH NETWORKS 2013
package net.sf.fmj.media.rtp;

import com.sun.media.util.*;

public class ConfigUtils
{
    public static String getConfig(String key, String defaultValue)
    {
        //We guarantee that this namespace will only be used for strings.
        String result = (String)Registry.get("jitterbuffer_" + key);

        if (result == null)
        {
            result = defaultValue;
        }

        return result;
    }

    public static int getIntConfig(String key, int defaultValue)
    {
        return Integer.parseInt(getConfig(key, String.valueOf(defaultValue)));
    }
}
