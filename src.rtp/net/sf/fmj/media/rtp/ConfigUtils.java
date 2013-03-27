// ConfigUtils.java
// (C) COPYRIGHT METASWITCH NETWORKS 2013
package net.sf.fmj.media.rtp;

import net.sf.fmj.media.*;

import com.sun.media.util.*;

public class ConfigUtils
{
    public static String getConfig(String key, String defaultValue)
    {
        //We guarantee that this namespace will only be used for strings (and
        // not general objects)
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

    public static boolean getBooleanConfig(String key, boolean defaultValue)
    {
        String valueFromConfig = getConfig(key, String.valueOf(defaultValue));
        boolean result = Boolean.parseBoolean(valueFromConfig);
        Log.info(String.format("Asked for %s with default (%s)\n" +
                               "Read %s from config which resulted in %s",
                               key,
                               defaultValue,
                               valueFromConfig,
                               result));
        return result;
    }
}
