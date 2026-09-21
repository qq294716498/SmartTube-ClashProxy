package com.liskovsoft.smartyoutubetv2.tv.proxy;

import com.liskovsoft.smartyoutubetv2.common.proxy.EmbeddedProxyStartup;
import org.junit.Test;
import static org.junit.Assert.*;

public class EmbeddedProxyStartupTest {
    @Test public void retainedStateSupportsHomeOpenedAfterStartup() {
        EmbeddedProxyStartup.connecting();
        EmbeddedProxyStartup.ready();
        assertEquals(EmbeddedProxyStartup.State.READY, EmbeddedProxyStartup.getStatus().state);
        EmbeddedProxyStartup.off();
    }

    @Test public void failedNodeCanRecoverAndDetachedViewReceivesNoMoreSignals() {
        int[] signals = {0};
        Runnable listener = () -> signals[0]++;
        EmbeddedProxyStartup.addListener(listener);
        EmbeddedProxyStartup.addListener(listener);
        try {
            EmbeddedProxyStartup.connecting();
            EmbeddedProxyStartup.failed("节点不可用");
            assertEquals("节点不可用", EmbeddedProxyStartup.getStatus().message);
            EmbeddedProxyStartup.ready();
            assertNull(EmbeddedProxyStartup.getStatus().message);
            assertEquals(3, signals[0]);
        } finally {
            EmbeddedProxyStartup.removeListener(listener);
            EmbeddedProxyStartup.off();
        }
        assertEquals(3, signals[0]);
    }
}
