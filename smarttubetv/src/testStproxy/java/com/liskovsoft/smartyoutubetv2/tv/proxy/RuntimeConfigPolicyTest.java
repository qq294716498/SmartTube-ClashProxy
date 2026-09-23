package com.liskovsoft.smartyoutubetv2.tv.proxy;

import org.junit.Test;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class RuntimeConfigPolicyTest {
    @Test public void subscriptionCannotOpenPortsOrController() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("mixed-port", 9999);
        config.put("allow-lan", true);
        config.put("external-controller", "0.0.0.0:9090");
        config.put("external-controller-unix", "/tmp/control.sock");
        config.put("listeners", Arrays.asList("untrusted"));
        config.put("authentication", Arrays.asList("user:secret"));
        config.put("tcp-concurrent", false);
        config.put("sniffer", new LinkedHashMap<>());
        config.put("tun", new LinkedHashMap<>());
        RuntimeConfigPolicy.sanitize(config);
        assertEquals(7890, config.get("mixed-port"));
        assertEquals(false, config.get("allow-lan"));
        assertEquals("127.0.0.1", config.get("bind-address"));
        assertEquals(0, config.get("redir-port"));
        assertFalse(config.containsKey("external-controller"));
        assertFalse(config.containsKey("external-controller-unix"));
        assertFalse(config.containsKey("listeners"));
        assertFalse(config.containsKey("authentication"));
        assertEquals(false, config.get("tcp-concurrent"));
        assertTrue(config.containsKey("sniffer"));
        assertEquals(false, ((Map<?, ?>) config.get("tun")).get("enable"));
    }

    @Test public void retainProxyDefinitionsAndResolverButRemoveDnsListener() {
        Map<String, Object> config = new LinkedHashMap<>();
        Object proxies = Arrays.asList("example-node");
        config.put("proxies", proxies);
        Map<String, Object> dns = new LinkedHashMap<>();
        dns.put("listen", "0.0.0.0:53");
        dns.put("nameserver", Arrays.asList("https://resolver.example/dns-query"));
        config.put("dns", dns);
        config.put("rules", Arrays.asList("MATCH,DIRECT"));
        RuntimeConfigPolicy.sanitize(config);
        assertSame(proxies, config.get("proxies"));
        assertFalse(dns.containsKey("listen"));
        assertTrue(dns.containsKey("nameserver"));
        assertEquals("global", config.get("mode"));
        assertTrue(((java.util.List<?>) config.get("rules")).isEmpty());
    }

    @Test public void providerCachePathCannotEscapeAppHome() {
        Map<String, Object> provider = new LinkedHashMap<>();
        provider.put("type", "http");
        provider.put("path", "../../outside.yaml");
        Map<String, Object> providers = new LinkedHashMap<>();
        providers.put("remote", provider);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("proxy-providers", providers);
        RuntimeConfigPolicy.sanitize(config);
        assertTrue(String.valueOf(provider.get("path")).startsWith("./providers/app-"));
        assertFalse(String.valueOf(provider.get("path")).contains(".."));
    }

    @Test public void sanitizingTwiceDoesNotChangePolicy() {
        Map<String, Object> config = new LinkedHashMap<>();
        RuntimeConfigPolicy.sanitize(config);
        Map<String, Object> first = new LinkedHashMap<>(config);
        RuntimeConfigPolicy.sanitize(config);
        assertEquals(first, config);
    }
}
