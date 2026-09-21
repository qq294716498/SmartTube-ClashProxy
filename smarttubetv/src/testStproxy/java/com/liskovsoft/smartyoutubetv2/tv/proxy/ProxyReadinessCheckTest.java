package com.liskovsoft.smartyoutubetv2.tv.proxy;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class ProxyReadinessCheckTest {
    @Test public void waitsForCleanupThenFreshProbe() {
        List<String> events = new ArrayList<>();
        ProxyReadinessCheck.Result[] pending = new ProxyReadinessCheck.Result[2];
        ProxyReadinessCheck.run(() -> true,
                result -> { events.add("cleanup"); pending[0] = result; },
                result -> { events.add("probe"); pending[1] = result; },
                (data, error) -> { assertNull(error); events.add("ready"); });
        assertEquals(Arrays.asList("cleanup"), events);
        pending[0].complete(null, null);
        assertEquals(Arrays.asList("cleanup", "probe"), events);
        pending[1].complete("85", null);
        assertEquals(Arrays.asList("cleanup", "probe", "ready"), events);
    }

    @Test public void cleanupFailureNeverProbesOrReportsReady() {
        ProxyReadinessCheck.run(() -> true,
                result -> result.complete(null, "timeout"),
                result -> fail("must not probe before cleanup succeeds"),
                (data, error) -> assertNotNull(error));
    }

    @Test public void rejectsFailedOrInvalidLatency() {
        for (String delay : new String[]{null, "", "0", "-1", "timeout", "999999999999"}) {
            ProxyReadinessCheck.run(() -> true,
                    result -> result.complete(null, null),
                    result -> result.complete(delay, null),
                    (data, error) -> assertNotNull(error));
        }
        ProxyReadinessCheck.run(() -> true,
                result -> result.complete(null, null),
                result -> result.complete("12", "TLS failure"),
                (data, error) -> assertNotNull(error));
    }

    @Test public void disableDuringCleanupDoesNotLaunchProbe() {
        boolean[] current = {true};
        ProxyReadinessCheck.Result[] pending = new ProxyReadinessCheck.Result[1];
        ProxyReadinessCheck.run(() -> current[0], result -> pending[0] = result,
                result -> fail("stale operation must not probe"),
                (data, error) -> assertNotNull(error));
        current[0] = false;
        pending[0].complete(null, null);
    }

    @Test public void lateProbeCannotPublishOldRouteAsReady() {
        boolean[] current = {true};
        ProxyReadinessCheck.Result[] pending = new ProxyReadinessCheck.Result[1];
        ProxyReadinessCheck.run(() -> current[0], result -> result.complete(null, null),
                result -> pending[0] = result,
                (data, error) -> assertNotNull(error));
        current[0] = false;
        pending[0].complete("50", null);
    }
}
