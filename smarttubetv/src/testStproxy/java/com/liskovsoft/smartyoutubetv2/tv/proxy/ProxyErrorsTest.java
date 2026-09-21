package com.liskovsoft.smartyoutubetv2.tv.proxy;

import org.junit.Test;
import static org.junit.Assert.*;

public class ProxyErrorsTest {
    @Test public void removesUrlPathAndQueryCredentials() {
        String safe = ProxyErrors.redact("下载失败 https://example.test/private/config?token=abc123 token=xyz password: hidden");
        assertFalse(safe.contains("abc123"));
        assertFalse(safe.contains("xyz"));
        assertFalse(safe.contains("hidden"));
        assertFalse(safe.contains("/private"));
    }
    @Test public void preservesUsefulStatusAndBoundsOutput() {
        assertEquals("订阅服务器返回 404", ProxyErrors.redact("订阅服务器返回 404"));
        assertNull(ProxyErrors.redact(null));
        assertTrue(ProxyErrors.redact(new String(new char[1000]).replace('\0', 'a')).length() <= 601);
    }
    @Test public void redactsFullDiagnosticWithoutTruncatingTimeline() {
        String timeline = new String(new char[900]).replace('\0', 'a');
        String safe = ProxyErrors.redactAll(timeline + " https://example.test/config?token=abc password=hidden uuid:1234");
        assertTrue(safe.length() > 900);
        assertFalse(safe.contains("example.test"));
        assertFalse(safe.contains("hidden"));
        assertFalse(safe.contains("1234"));
    }
}
