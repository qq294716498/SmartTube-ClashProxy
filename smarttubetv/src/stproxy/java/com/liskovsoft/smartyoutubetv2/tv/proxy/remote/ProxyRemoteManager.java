package com.liskovsoft.smartyoutubetv2.tv.proxy.remote;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.tv.proxy.data.ProxyNodeManager;
import com.liskovsoft.smartyoutubetv2.tv.proxy.data.ProxyPreferences;
import com.liskovsoft.smartyoutubetv2.tv.proxy.data.SubscriptionManager;
import com.liskovsoft.smartyoutubetv2.tv.proxy.model.ProxyNode;
import com.liskovsoft.smartyoutubetv2.tv.proxy.model.SubscriptionProfile;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** A token-protected LAN page for managing the TV proxy from a phone. */
public final class ProxyRemoteManager {
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService CLIENTS = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "proxy-phone-client");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile ProxyRemoteManager instance;

    private final Context context;
    private final SubscriptionManager subscriptions;
    private final ProxyPreferences preferences;
    private final ProxyNodeManager nodes;
    private final String token;
    private final ServerSocket server;
    private final String address;

    private ProxyRemoteManager(Context context) throws Exception {
        this.context = context.getApplicationContext();
        subscriptions = new SubscriptionManager(this.context);
        preferences = new ProxyPreferences(this.context);
        nodes = new ProxyNodeManager(subscriptions);
        token = createToken();
        server = new ServerSocket(0);
        String host = findLanAddress();
        if (host == null) {
            server.close();
            throw new IllegalStateException("电视未连接局域网");
        }
        address = "http://" + host + ":" + server.getLocalPort() + "/?token=" + token;
        Thread accept = new Thread(this::acceptLoop, "proxy-phone-server");
        accept.setDaemon(true);
        accept.start();
    }

    public static void show(Context context) {
        try {
            ProxyRemoteManager manager = instance;
            if (manager == null || manager.server.isClosed()) {
                synchronized (ProxyRemoteManager.class) {
                    manager = instance;
                    if (manager == null || manager.server.isClosed()) {
                        manager = new ProxyRemoteManager(context);
                        instance = manager;
                    }
                }
            }
            manager.showQr(context);
        } catch (Exception error) {
            MessageHelpers.showLongMessage(context, "无法启动手机管理：" + safeMessage(error));
        }
    }

    private void showQr(Context activityContext) throws Exception {
        float density = activityContext.getResources().getDisplayMetrics().density;
        int padding = (int) (24 * density);
        LinearLayout layout = new LinearLayout(activityContext);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setPadding(padding, padding, padding, padding);

        ImageView qr = new ImageView(activityContext);
        qr.setImageBitmap(createQr(address, (int) (300 * density)));
        int size = (int) (320 * density);
        layout.addView(qr, new LinearLayout.LayoutParams(size, size));

        TextView instructions = new TextView(activityContext);
        instructions.setText("手机与电视连接同一局域网后扫码。\n可在手机上添加、编辑、删除订阅并选择节点。\n\n" + address);
        instructions.setTextSize(18);
        instructions.setGravity(Gravity.CENTER);
        instructions.setTextIsSelectable(true);
        layout.addView(instructions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(activityContext)
                .setTitle("手机扫码管理")
                .setView(layout)
                .setNegativeButton("关闭", null)
                .show();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                socket.setSoTimeout(10_000);
                CLIENTS.execute(() -> handle(socket));
            } catch (Exception ignored) {
                if (server.isClosed()) return;
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket client = socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(
                     client.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                     client.getOutputStream(), StandardCharsets.UTF_8))) {
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.length() > 4096) return;
            String[] request = requestLine.split(" ");
            if (request.length < 2) return;
            String method = request[0];
            String target = request[1];
            int contentLength = 0;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0 && "content-length".equalsIgnoreCase(line.substring(0, colon).trim())) {
                    contentLength = Integer.parseInt(line.substring(colon + 1).trim());
                }
            }
            if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
                send(writer, 413, "text/plain; charset=utf-8", "请求过大");
                return;
            }
            char[] bodyChars = new char[contentLength];
            int read = 0;
            while (read < contentLength) {
                int count = reader.read(bodyChars, read, contentLength - read);
                if (count < 0) break;
                read += count;
            }
            String body = new String(bodyChars, 0, read);
            String path = target;
            String query = "";
            int question = target.indexOf('?');
            if (question >= 0) {
                path = target.substring(0, question);
                query = target.substring(question + 1);
            }
            Map<String, String> parameters = parseForm(query);
            parameters.putAll(parseForm(body));
            if (!token.equals(parameters.get("token"))) {
                send(writer, 403, "text/plain; charset=utf-8", "访问密钥无效，请重新扫描电视二维码");
                return;
            }
            if ("POST".equals(method)) {
                handleAction(path, parameters);
                redirect(writer);
            } else {
                send(writer, 200, "text/html; charset=utf-8", renderPage());
            }
        } catch (Exception ignored) {
        }
    }

    private void handleAction(String path, Map<String, String> values) {
        String id = values.get("id");
        if ("/save".equals(path)) {
            String url = trim(values.get("url"));
            if (!isHttps(url)) return;
            SubscriptionProfile saved = id == null || id.isEmpty()
                    ? subscriptions.add(values.get("name"), url)
                    : subscriptions.edit(id, values.get("name"), url);
            if (saved != null) subscriptions.update(saved.id, (profile, error) -> { });
        } else if ("/delete".equals(path)) {
            subscriptions.delete(id);
        } else if ("/activate".equals(path)) {
            subscriptions.activate(id, (profile, error) -> { });
        } else if ("/node".equals(path)) {
            SubscriptionProfile profile = subscriptions.get(id);
            String group = values.get("group");
            String nodeName = values.get("node");
            int count = parseInt(values.get("count"));
            if (profile != null && group != null && nodeName != null) {
                ProxyNode node = new ProxyNode(nodeName, nodeName, "Unknown", false);
                nodes.switchNode(profile, group, node, count, error -> { });
            }
        }
        MAIN.post(() -> MessageHelpers.showMessage(context, "手机端代理设置已更新"));
    }

    private String renderPage() {
        List<SubscriptionProfile> profiles = subscriptions.list();
        SubscriptionProfile active = subscriptions.getActive();
        StringBuilder html = new StringBuilder(8192);
        html.append("<!doctype html><html lang=zh-CN><head><meta charset=utf-8>")
                .append("<meta name=viewport content='width=device-width,initial-scale=1'>")
                .append("<title>优兔喵视频代理管理</title><style>")
                .append("body{margin:0;background:#07101f;color:#eef5ff;font-family:system-ui;padding:18px}")
                .append("main{max-width:720px;margin:auto}h1{font-size:25px}.card{background:#111d31;border:1px solid #263957;border-radius:16px;padding:16px;margin:14px 0}")
                .append("input,select,button{box-sizing:border-box;width:100%;font-size:16px;border-radius:10px;padding:12px;margin:6px 0}")
                .append("input,select{color:#fff;background:#081324;border:1px solid #405679}button{border:0;background:#1677ff;color:#fff;font-weight:700}")
                .append(".danger{background:#a52d36}.secondary{background:#344967}.ok{color:#58e6a9}.muted{color:#9fb0c8;font-size:14px}</style></head><body><main>")
                .append("<h1>优兔喵视频 · 代理管理</h1><p class=muted>此页面只在当前局域网和本次电视应用运行期间有效。</p>");
        for (SubscriptionProfile profile : profiles) {
            html.append("<section class=card><h2>").append(escape(profile.name));
            if (profile.active) html.append(" <span class=ok>● 当前</span>");
            html.append("</h2><form method=post action='/save'>").append(hidden())
                    .append(hidden("id", profile.id))
                    .append("<input name=name placeholder='订阅名称' value='").append(attr(profile.name)).append("'>")
                    .append("<input name=url type=url required pattern='https://.*' placeholder='HTTPS 订阅地址' value='").append(attr(profile.url)).append("'>")
                    .append("<button>保存并更新</button></form>");
            if (!profile.active) {
                html.append(actionForm("/activate", profile.id, "设为当前订阅", "secondary"));
            }
            html.append(actionForm("/delete", profile.id, "删除订阅", "danger")).append("</section>");
        }
        html.append("<section class=card><h2>添加订阅</h2><form method=post action='/save'>").append(hidden())
                .append("<input name=name placeholder='订阅名称（可留空）'>")
                .append("<input name=url type=url required pattern='https://.*' placeholder='粘贴 HTTPS 订阅地址'>")
                .append("<button>添加并更新</button></form></section>");
        if (active != null && preferences.isEnabled()) {
            NodeSnapshot snapshot = loadNodes(active);
            html.append("<section class=card><h2>节点选择</h2>");
            if (snapshot.error != null) {
                html.append("<p class=muted>").append(escape(snapshot.error)).append("</p>");
            } else {
                html.append("<form method=post action='/node'>").append(hidden())
                        .append(hidden("id", active.id)).append(hidden("group", snapshot.group))
                        .append(hidden("count", String.valueOf(snapshot.nodes.size())))
                        .append("<select name=node>");
                for (ProxyNode node : snapshot.nodes) {
                    html.append("<option value='").append(attr(node.runtimeName)).append("'")
                            .append(node.selected ? " selected" : "").append(">")
                            .append(escape(node.name)).append("</option>");
                }
                html.append("</select><button>切换节点</button></form>");
            }
            html.append("</section>");
        } else {
            html.append("<section class=card><h2>节点选择</h2><p class=muted>请先在电视上开启内置代理并选择有效订阅。</p></section>");
        }
        return html.append("</main></body></html>").toString();
    }

    private NodeSnapshot loadNodes(SubscriptionProfile active) {
        CountDownLatch done = new CountDownLatch(1);
        NodeSnapshot snapshot = new NodeSnapshot();
        nodes.query(active, (group, result, error) -> {
            snapshot.group = group;
            snapshot.nodes = result == null ? Collections.emptyList() : result;
            snapshot.error = error;
            done.countDown();
        });
        try {
            if (!done.await(6, TimeUnit.SECONDS)) snapshot.error = "读取节点超时，请刷新页面重试";
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            snapshot.error = "读取节点已取消";
        }
        return snapshot;
    }

    private String hidden() { return hidden("token", token); }

    private static String hidden(String name, String value) {
        return "<input type=hidden name='" + attr(name) + "' value='" + attr(value) + "'>";
    }

    private String actionForm(String path, String id, String label, String css) {
        return "<form method=post action='" + path + "'>" + hidden() + hidden("id", id) +
                "<button class='" + css + "'>" + escape(label) + "</button></form>";
    }

    private void redirect(BufferedWriter writer) throws Exception {
        writer.write("HTTP/1.1 303 See Other\r\nLocation: /?token=" + token +
                "\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: 0\r\n\r\n");
        writer.flush();
    }

    private static void send(BufferedWriter writer, int code, String type, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writer.write("HTTP/1.1 " + code + (code == 200 ? " OK" : " Error") + "\r\n");
        writer.write("Content-Type: " + type + "\r\nContent-Length: " + bytes.length +
                "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n");
        writer.flush();
        socketWrite(writer, value);
    }

    private static void socketWrite(BufferedWriter writer, String value) throws Exception {
        writer.write(value);
        writer.flush();
    }

    private static Map<String, String> parseForm(String value) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        if (value == null || value.isEmpty()) return result;
        for (String part : value.split("&")) {
            int equals = part.indexOf('=');
            String key = equals < 0 ? part : part.substring(0, equals);
            String item = equals < 0 ? "" : part.substring(equals + 1);
            result.put(URLDecoder.decode(key, "UTF-8"), URLDecoder.decode(item, "UTF-8"));
        }
        return result;
    }

    private static Bitmap createQr(String value, int size) throws Exception {
        BitMatrix matrix = new MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, size, size);
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) pixels[y * size + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
        }
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size);
        return bitmap;
    }

    private static String findLanAddress() throws Exception {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface network = interfaces.nextElement();
            if (!network.isUp() || network.isLoopback()) continue;
            Enumeration<InetAddress> addresses = network.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (address instanceof Inet4Address && !address.isLoopbackAddress() && address.isSiteLocalAddress()) {
                    return address.getHostAddress();
                }
            }
        }
        return null;
    }

    private static String createToken() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder value = new StringBuilder(32);
        for (byte item : bytes) value.append(String.format("%02x", item & 0xff));
        return value.toString();
    }

    private static boolean isHttps(String value) {
        try {
            URL url = new URL(value);
            return "https".equalsIgnoreCase(url.getProtocol()) && url.getHost() != null && !url.getHost().isEmpty();
        } catch (Exception ignored) { return false; }
    }

    private static int parseInt(String value) {
        try { return Integer.parseInt(value); } catch (Exception ignored) { return 0; }
    }

    private static String trim(String value) { return value == null ? "" : value.trim(); }
    private static String safeMessage(Exception error) {
        return error.getMessage() == null ? "未知错误" : error.getMessage();
    }
    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
    private static String attr(String value) {
        return escape(value).replace("'", "&#39;").replace("\"", "&quot;");
    }

    private static final class NodeSnapshot {
        String group;
        List<ProxyNode> nodes = new ArrayList<>();
        String error;
    }
}
