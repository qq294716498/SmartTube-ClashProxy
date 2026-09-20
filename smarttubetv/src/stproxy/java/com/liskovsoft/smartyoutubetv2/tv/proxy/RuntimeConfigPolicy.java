package com.liskovsoft.smartyoutubetv2.tv.proxy;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Subscription data never controls app listeners, controllers or VPN routing. */
public final class RuntimeConfigPolicy {
    private RuntimeConfigPolicy() { }

    public static void writeSafe(File source, File target) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(30);
        options.setCodePointLimit(10 * 1024 * 1024);
        options.setNestingDepthLimit(50);
        Object loaded;
        try (FileInputStream input = new FileInputStream(source)) {
            loaded = new Yaml(new SafeConstructor(options)).load(input);
        } catch (RuntimeException error) {
            throw new IOException("订阅不是有效的 Clash YAML（请检查格式或重复字段）");
        }
        if (!(loaded instanceof Map)) throw new IOException("订阅必须是 Clash YAML 配置");
        Map<String, Object> config = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) loaded).entrySet()) {
            if (!(entry.getKey() instanceof String)) throw new IOException("订阅字段名称无效");
            config.put((String) entry.getKey(), entry.getValue());
        }
        sanitize(config);
        DumperOptions dump = new DumperOptions();
        dump.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        try (FileOutputStream output = new FileOutputStream(target)) {
            output.write(new Yaml(dump).dump(config).getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        }
    }

    static void sanitize(Map<String, Object> config) {
        for (String key : new String[]{"external-controller", "external-controller-tls",
                "external-controller-unix", "external-controller-pipe", "external-ui",
                "external-ui-url", "external-ui-name", "secret", "listeners", "tunnels",
                "interface-name", "routing-mark", "authentication", "skip-auth-prefixes",
                "lan-allowed-ips", "lan-disallowed-ips", "iptables", "ebpf", "script",
                "rule-providers", "sub-rules", "geox-url"}) config.remove(key);
        config.put("mixed-port", MihomoCoreManager.MIXED_PORT);
        config.put("port", 0);
        config.put("socks-port", 0);
        config.put("redir-port", 0);
        config.put("tproxy-port", 0);
        config.put("allow-lan", false);
        config.put("bind-address", MihomoCoreManager.LOOPBACK_HOST);
        config.put("mode", "global");
        config.put("log-level", "warning");
        config.put("ipv6", false);
        Map<String, Object> tun = new LinkedHashMap<>();
        tun.put("enable", false);
        config.put("tun", tun);
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("store-selected", false);
        profile.put("store-fake-ip", false);
        config.put("profile", profile);
        // GLOBAL ignores subscription rules. Avoid unnecessary geo/rule downloads on import.
        config.put("rules", new ArrayList<>());
        Object dns = config.get("dns");
        if (dns instanceof Map) ((Map<?, ?>) dns).remove("listen");
        Object providers = config.get("proxy-providers");
        if (providers instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) providers).entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Map && "http".equals(((Map<?, ?>) value).get("type"))) {
                    @SuppressWarnings("unchecked") Map<Object, Object> provider = (Map<Object, Object>) value;
                    // Different subscriptions must never share stale provider files.
                    String key = String.valueOf(entry.getKey()) + "\n" + String.valueOf(provider.get("url"));
                    provider.put("path", "./providers/app-" + java.util.UUID.nameUUIDFromBytes(
                            key.getBytes(StandardCharsets.UTF_8)) + ".yaml");
                }
            }
        }
    }
}
