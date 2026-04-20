package dev.xssmusashi.atlas.mc.command;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Tiny helper so command code doesn't depend on AtlasMod's logger directly. */
final class AtlasModLog {
    private AtlasModLog() {}
    private static final Logger LOG = LoggerFactory.getLogger("atlas");
    static void warn(String msg) { LOG.warn(msg); }
}
