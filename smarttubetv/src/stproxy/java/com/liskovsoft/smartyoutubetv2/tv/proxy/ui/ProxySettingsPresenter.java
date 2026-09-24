package com.liskovsoft.smartyoutubetv2.tv.proxy.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.liskovsoft.smartyoutubetv2.tv.proxy.MihomoCoreManager;
import com.liskovsoft.smartyoutubetv2.tv.proxy.ProxyErrors;
import com.liskovsoft.smartyoutubetv2.tv.proxy.ProxyRuntimeCoordinator;
import com.liskovsoft.smartyoutubetv2.tv.proxy.data.ProxyHealthChecker;
import com.liskovsoft.smartyoutubetv2.tv.proxy.data.ProxyNodeManager;
import com.liskovsoft.smartyoutubetv2.tv.proxy.data.ProxyPreferences;
import com.liskovsoft.smartyoutubetv2.tv.proxy.data.SubscriptionManager;
import com.liskovsoft.smartyoutubetv2.tv.proxy.model.ProxyNode;
import com.liskovsoft.smartyoutubetv2.tv.proxy.model.SubscriptionProfile;
import com.liskovsoft.smartyoutubetv2.tv.proxy.remote.ProxyRemoteManager;
import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A stable TV workspace, not a stack of settings dialogs. D-pad focus never triggers mutations. */
public final class ProxySettingsPresenter {
    private static final int BG = Color.rgb(10, 17, 30);
    private static final int CARD = Color.rgb(22, 34, 52);
    private static final int TEXT = Color.rgb(234, 241, 251);
    private static final int MUTED = Color.rgb(160, 178, 200);
    private static final int BLUE = Color.rgb(70, 152, 255);
    private static final int GREEN = Color.rgb(74, 218, 169);
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SubscriptionManager subscriptions;
    private final ProxyNodeManager nodes;
    private final ProxyPreferences preferences;
    private final List<ProxyNode> nodeList = new ArrayList<>();
    private Dialog dialog;
    private LinearLayout content;
    private TextView status, summary, feedback, power;
    private ListView nodeView;
    private NodeAdapter nodeAdapter;
    private ProxyNodeManager.TestSession testSession;
    private int section;
    private long shownEpoch = -1;
    private String shownProfile;
    private boolean loadingNodes, switching, testing, diagnosing;
    private int testTicket;
    private String focusedNode;
    private String group = "GLOBAL";
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!visible()) return;
            refreshStatus();
            if (section == 0 && !ProxyRuntimeCoordinator.isBusy()
                    && ProxyRuntimeCoordinator.isRoutingReady(context)
                    && shownEpoch != ProxyRuntimeCoordinator.getEpoch()) loadNodes();
            main.postDelayed(this, 800);
        }
    };

    private ProxySettingsPresenter(Context context) {
        this.context = context;
        subscriptions = new SubscriptionManager(context);
        nodes = new ProxyNodeManager(subscriptions);
        preferences = new ProxyPreferences(context);
    }
    public static void show(Context context) { new ProxySettingsPresenter(context).open(); }
    public static String getHomeLabel(Context context) {
        if (!ProxyRuntimeCoordinator.isRoutingReady(context)) return null;
        SubscriptionProfile active = new SubscriptionManager(context).getActive();
        return active == null ? null : active.selectedNode;
    }
    public static int getHomeState(Context context) {
        if (!new ProxyPreferences(context).isEnabled()) return 0;
        if (ProxyRuntimeCoordinator.isBusy()) return 1;
        if (ProxyRuntimeCoordinator.getLastError() != null
                || MihomoCoreManager.getState() == MihomoCoreManager.State.FAILED) return 3;
        return ProxyRuntimeCoordinator.isRoutingReady(context) ? 2 : 1;
    }

    private void open() {
        dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout shell = column();
        shell.setPadding(dp(24), dp(20), dp(24), dp(16));
        shell.setBackgroundColor(BG);
        LinearLayout header = row();
        TextView title = text("Clash 代理中心", 25, TEXT);
        title.setTypeface(null, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(button("返回视频", () -> dialog.dismiss()));
        shell.addView(header);
        summary = text("", 15, MUTED);
        summary.setPadding(0, dp(4), 0, dp(12));
        shell.addView(summary);

        LinearLayout body = row();
        LinearLayout rail = column();
        rail.setPadding(0, 0, dp(18), 0);
        status = text("", 18, GREEN);
        status.setPadding(dp(12), dp(10), dp(12), dp(10));
        rail.addView(status);
        power = button("", this::toggle);
        rail.addView(power);
        rail.addView(button("连接与节点", () -> showSection(0)));
        rail.addView(button("订阅管理", () -> showSection(1)));
        rail.addView(button("检测与日志", () -> showSection(2)));
        rail.addView(button("手机扫码管理", () -> ProxyRemoteManager.show(context)));
        TextView help = text("方向键移动\n确定键操作\n返回键回到视频", 13, MUTED);
        help.setPadding(dp(12), dp(18), dp(8), 0);
        rail.addView(help);
        content = column();
        body.addView(rail, new LinearLayout.LayoutParams(dp(190), -1));
        body.addView(content, new LinearLayout.LayoutParams(0, -1, 1));
        shell.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        feedback = text("仅代理本软件，不影响电视上的其他应用。", 14, MUTED);
        feedback.setPadding(dp(8), dp(12), dp(8), 0);
        feedback.setMaxLines(3);
        shell.addView(feedback);
        dialog.setContentView(shell);
        dialog.setOnDismissListener(ignored -> {
            cancelTests();
            main.removeCallbacksAndMessages(null);
        });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(-1, -1);
            window.setBackgroundDrawableResource(android.R.color.transparent);
        }
        showSection(0);
        refreshStatus();
        power.requestFocus();
        main.post(ticker);
    }

    private void showSection(int value) {
        cancelTests();
        section = value;
        content.removeAllViews();
        nodeView = null;
        nodeAdapter = null;
        if (value == 0) showNodes();
        else if (value == 1) showSubscriptions();
        else showTools();
    }

    private void refreshStatus() {
        int state = getHomeState(context);
        status.setText(state == 2 ? "● 代理已开启" : state == 1 ? "◐ 正在连接" : state == 3 ? "● 连接异常" : "○ 代理已关闭");
        status.setTextColor(state == 2 ? GREEN : state == 3 ? Color.rgb(255, 125, 125) : MUTED);
        power.setText(preferences.isEnabled() ? "关闭代理" : "开启代理");
        SubscriptionProfile profile = subscriptions.getActive();
        summary.setText(profile == null ? "尚未添加订阅 · 可用手机扫码输入地址"
                : profile.name + "  /  " + (profile.selectedNode == null ? "尚未选择节点" : profile.selectedNode));
    }

    private void toggle() {
        cancelTests();
        boolean enable = !preferences.isEnabled();
        say(enable ? "正在启动核心、应用订阅并恢复节点…" : "正在关闭代理…");
        ProxyRuntimeCoordinator.setEnabled(context, enable, error -> ui(() -> {
            say(error == null ? (enable ? "代理已接管应用请求，可检测 YouTube 连通性。" : "代理已关闭，应用使用直连。") : error);
            refreshStatus();
            if (section == 0) { shownEpoch = -1; loadNodes(); }
        }));
    }

    private void showNodes() {
        content.addView(text("节点选择", 21, TEXT));
        TextView hint = text("确定键切换节点 · 绿色勾选为实际使用节点 · 延迟不是下载速度", 13, MUTED);
        hint.setPadding(0, dp(4), 0, dp(8));
        content.addView(hint);
        LinearLayout actions = row();
        actions.addView(button("全部测延迟", () -> test(false)));
        actions.addView(button("测选中节点", () -> test(true)));
        actions.addView(button("停止", () -> { cancelTests(); say("已停止测速，保留已完成结果。"); }));
        content.addView(actions);
        LinearLayout secondary = row();
        secondary.addView(button("按延迟排序", this::sortNodes));
        secondary.addView(button("刷新节点", () -> { shownEpoch = -1; loadNodes(); }));
        secondary.addView(button("检测 YouTube", this::diagnose));
        content.addView(secondary);
        nodeView = new ListView(context);
        nodeView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        nodeView.setDividerHeight(dp(6));
        nodeView.setSelector(focusBackground());
        nodeView.setItemsCanFocus(false);
        nodeAdapter = new NodeAdapter();
        nodeView.setAdapter(nodeAdapter);
        nodeView.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < nodeList.size()) focusedNode = nodeList.get(position).runtimeName;
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        nodeView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= nodeList.size()) {
                say("节点列表正在刷新，请重新选择。");
                return;
            }
            try {
                switchNode(nodeList.get(position));
            } catch (Throwable error) {
                switching = false;
                MihomoCoreManager.recordDiagnosticEvent(
                        "节点点击异常：" + error.getClass().getName() + ": " + error.getMessage());
                say("节点切换发生异常，请扫码查看诊断日志。");
            }
        });
        content.addView(nodeView, new LinearLayout.LayoutParams(-1, 0, 1));
        loadNodes();
    }

    private void loadNodes() {
        if (loadingNodes || section != 0) return;
        SubscriptionProfile profile = subscriptions.getActive();
        if (profile == null || !ProxyRuntimeCoordinator.isRoutingReady(context)) {
            nodeList.clear();
            if (nodeAdapter != null) nodeAdapter.notifyDataSetChanged();
            say(profile == null ? "先到“订阅管理”添加订阅，或使用手机扫码。" : "开启代理后即可读取、检测和选择节点。");
            return;
        }
        loadingNodes = true;
        long epoch = ProxyRuntimeCoordinator.getEpoch();
        shownEpoch = epoch;
        nodes.query(profile, (runtimeGroup, list, error) -> ui(() -> {
            loadingNodes = false;
            if (epoch != ProxyRuntimeCoordinator.getEpoch() || section != 0) return;
            if (error != null) { say(error); return; }
            cancelTests();
            group = runtimeGroup;
            shownProfile = profile.id;
            shownEpoch = epoch;
            nodeList.clear();
            nodeList.addAll(list);
            if (nodeAdapter != null) nodeAdapter.notifyDataSetChanged();
            say(list.isEmpty() ? "尚未读取到节点，请稍后刷新或更新订阅。" : "共 " + list.size() + " 个节点；选择后按确定即可切换。");
        }));
    }

    private void switchNode(ProxyNode node) {
        if (switching || ProxyRuntimeCoordinator.isBusy()) { say("正在切换，请稍后。"); return; }
        SubscriptionProfile profile = subscriptions.getActive();
        if (profile == null || !profile.id.equals(shownProfile)) { loadNodes(); return; }
        if (node.selected) { say("当前已经在使用此节点。"); return; }
        switching = true;
        cancelTests();
        say("正在切换到 " + node.name + "…");
        nodes.switchNode(profile, group, node, nodeList.size(), error -> ui(() -> {
            switching = false;
            if (error == null) {
                for (ProxyNode item : nodeList) item.selected = item.runtimeName.equals(node.runtimeName);
                if (nodeAdapter != null) nodeAdapter.notifyDataSetChanged();
                refreshStatus();
                say("已切换到 " + node.name + "；已有视频连接会重连，可能短暂停顿。");
            } else say(error);
        }));
    }

    private void test(boolean single) {
        if (testing || switching || ProxyRuntimeCoordinator.isBusy()) { say("已有操作进行中，可先停止测速。"); return; }
        SubscriptionProfile profile = subscriptions.getActive();
        if (profile == null || !profile.id.equals(shownProfile) || !ProxyRuntimeCoordinator.isRoutingReady(context)) {
            say("请先开启代理并读取节点。"); return;
        }
        List<ProxyNode> targets = new ArrayList<>(nodeList);
        if (single) {
            int index = -1;
            for (int i = 0; i < nodeList.size(); i++) if (nodeList.get(i).runtimeName.equals(focusedNode)) index = i;
            if (index < 0 || index >= nodeList.size()) {
                index = 0;
                for (int i = 0; i < nodeList.size(); i++) if (nodeList.get(i).selected) index = i;
            }
            targets = nodeList.isEmpty() ? Collections.emptyList() : Collections.singletonList(nodeList.get(index));
        }
        if (targets.isEmpty()) { say("没有可测试节点。"); return; }
        testing = true;
        int ticket = ++testTicket;
        say("正在测试延迟 0 / " + targets.size() + "，可继续移动焦点或停止。");
        testSession = nodes.testAll(profile, targets, new ProxyNodeManager.DelayProgress() {
            @Override public void onProgress(int completed, int total, ProxyNode node) {
                ui(() -> {
                    if (!testing || ticket != testTicket) return;
                    if (nodeAdapter != null) nodeAdapter.notifyDataSetChanged();
                    say("正在测试延迟 " + completed + " / " + total + " · " + node.name + " " + delay(node.delayMs));
                });
            }
            @Override public void onComplete() {
                ui(() -> {
                    if (!testing || ticket != testTicket) return;
                    testing = false;
                    if (nodeAdapter != null) nodeAdapter.notifyDataSetChanged();
                    say("延迟测试结束。可按延迟排序；超时不一定代表节点永久不可用。");
                });
            }
        });
        if (nodeAdapter != null) nodeAdapter.notifyDataSetChanged();
    }

    private void cancelTests() {
        testTicket++;
        if (testSession != null) testSession.cancel();
        testSession = null;
        testing = false;
        for (ProxyNode node : nodeList) if (node.delayMs == ProxyNode.DELAY_TESTING) node.delayMs = ProxyNode.DELAY_UNKNOWN;
        if (nodeAdapter != null) nodeAdapter.notifyDataSetChanged();
    }

    private void sortNodes() {
        if (testing) { say("请等待测速结束，或先停止。"); return; }
        Collections.sort(nodeList, (a, b) -> Integer.compare(a.delayMs > 0 ? a.delayMs : Integer.MAX_VALUE,
                b.delayMs > 0 ? b.delayMs : Integer.MAX_VALUE));
        if (nodeAdapter != null) nodeAdapter.notifyDataSetChanged();
        if (nodeView != null && !nodeList.isEmpty()) { nodeView.setSelection(0); nodeView.requestFocus(); }
    }

    private void showSubscriptions() {
        content.addView(text("订阅管理", 21, TEXT));
        LinearLayout actions = row();
        actions.addView(button("+ 添加订阅", () -> edit(null)));
        actions.addView(button("手机扫码输入", () -> ProxyRemoteManager.show(context)));
        actions.addView(button("刷新列表", () -> showSection(1)));
        content.addView(actions);
        List<SubscriptionProfile> profiles = subscriptions.list();
        ListView list = new ListView(context);
        list.setSelector(focusBackground());
        list.setDividerHeight(dp(6));
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return profiles.size(); }
            @Override public Object getItem(int position) { return profiles.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                SubscriptionProfile p = profiles.get(position);
                boolean cached = p.configPath != null && new File(p.configPath).isFile();
                TextView row = text((p.active ? "✓ " : "") + p.name + "\n"
                        + (cached ? (p.lastUpdateSuccess ? "配置可用" : "保留上次配置") : "尚无可用配置")
                        + " · " + masked(p.url) + "\n确定键：使用 / 更新 / 编辑 / 删除", 16, TEXT);
                row.setPadding(dp(16), dp(12), dp(16), dp(12));
                row.setMinHeight(dp(88));
                return row;
            }
        });
        list.setOnItemClickListener((parent, view, position, id) -> subscriptionActions(profiles.get(position)));
        content.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        if (profiles.isEmpty()) say("支持 HTTP / HTTPS；电视输入不方便时，使用手机扫码管理。");
    }

    private void subscriptionActions(SubscriptionProfile profile) {
        new AlertDialog.Builder(context).setTitle(profile.name)
                .setItems(new String[]{"使用此订阅", "更新订阅", "编辑名称与地址", "删除订阅"}, (d, which) -> {
                    if (which == 0) {
                        say("正在切换订阅…");
                        subscriptions.activate(profile.id, (updated, error) -> ui(() -> {
                            say(error == null ? "已切换订阅" + (preferences.isEnabled() ? "" : "，点击开启代理开始使用。") : error);
                            refreshStatus();
                            if (section == 1) showSection(1);
                        }));
                    } else if (which == 1) update(profile);
                    else if (which == 2) edit(profile);
                    else new AlertDialog.Builder(context).setTitle("删除“" + profile.name + "”？")
                            .setMessage(profile.active ? "删除当前订阅会关闭代理，不会自动启用其他订阅。" : "只删除本机保存的订阅和配置。")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("删除", (confirm, id) -> {
                                subscriptions.delete(profile.id);
                                showSection(1); refreshStatus(); say("订阅已删除。");
                            }).show();
                }).setNegativeButton("返回", null).show();
    }

    private void update(SubscriptionProfile profile) {
        say("正在下载并校验 " + profile.name + "…");
        subscriptions.update(profile.id, (updated, error) -> ui(() -> {
            if (section == 1) showSection(1);
            refreshStatus();
            say(error == null ? "订阅更新成功。" : "更新失败：" + error + "。已有有效配置不会因下载失败而删除。");
        }));
    }

    private void edit(SubscriptionProfile existing) {
        LinearLayout fields = column();
        fields.setPadding(dp(24), dp(12), dp(24), 0);
        EditText name = new EditText(context);
        name.setSingleLine(true); name.setHint("名称（可留空）");
        name.setText(existing == null ? "" : existing.name);
        fields.addView(name);
        EditText url = new EditText(context);
        url.setSingleLine(true); url.setHint("HTTP/HTTPS 订阅地址");
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(existing == null ? "" : existing.url);
        fields.addView(url);
        AlertDialog form = new AlertDialog.Builder(context).setTitle(existing == null ? "添加订阅" : "编辑订阅")
                .setView(fields).setNegativeButton("取消", null).setPositiveButton("保存并更新", null).create();
        form.setOnShowListener(ignored -> form.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = url.getText().toString().trim();
            if (!validUrl(value)) { url.setError("请输入完整 HTTP 或 HTTPS 地址"); url.requestFocus(); return; }
            SubscriptionProfile saved = existing == null ? subscriptions.add(name.getText().toString(), value)
                    : subscriptions.edit(existing.id, name.getText().toString(), value);
            form.dismiss();
            if (saved != null) update(saved);
        }));
        form.show();
    }

    private void showTools() {
        content.addView(text("检测与日志", 21, TEXT));
        content.addView(text("连通检测不是带宽测速，也不能代替真实视频播放测试。", 14, MUTED));
        content.addView(button("检测 YouTube 与视频域名", this::diagnose));
        content.addView(button("重新应用当前订阅", () -> {
            if (!preferences.isEnabled()) { say("请先开启代理。"); return; }
            SubscriptionProfile p = subscriptions.getActive();
            if (p == null) return;
            say("正在重新应用订阅…");
            ProxyRuntimeCoordinator.activate(context, p, error -> ui(() -> {
                refreshStatus(); say(error == null ? "重新连接完成。" : error);
            }));
        }));
        content.addView(button("Mihomo 核心自检", () -> {
            say("正在检查核心…");
            MihomoCoreManager.ensureStarted(context, (data, error) -> ui(() ->
                    say(error == null ? "核心已就绪。核心就绪不等于 YouTube 已连通。" : error)));
        }));
        content.addView(button("导出诊断日志", () -> {
            say("正在导出日志…");
            new Thread(() -> {
                String result = MihomoCoreManager.exportDiagnosticLog(context);
                ui(() -> say(result));
            }, "proxy-log-export").start();
        }));
        TextView note = text("排查顺序：订阅更新 → 开启代理 → 选择节点 → 检测 YouTube → 返回播放。\n\n切换节点后，已开始的请求可以完成；新请求使用新节点。", 15, MUTED);
        note.setPadding(dp(12), dp(20), dp(12), 0);
        content.addView(note);
    }

    private void diagnose() {
        if (diagnosing) { say("检测进行中…"); return; }
        if (!ProxyRuntimeCoordinator.isRoutingReady(context)) { say("请先开启代理并等待节点应用完成。"); return; }
        diagnosing = true;
        long epoch = ProxyRuntimeCoordinator.getEpoch();
        say("正在通过本地代理检测 YouTube / 视频域名…");
        ProxyHealthChecker.check(result -> ui(() -> {
            diagnosing = false;
            if (epoch != ProxyRuntimeCoordinator.getEpoch()) { say("检测期间代理已变化，请重新检测。"); return; }
            new AlertDialog.Builder(context).setTitle("代理连通检测")
                    .setMessage("本地代理端口：" + result(result.localProxy)
                            + "\nYouTube：" + result(result.youtube) + detail(result.youtubeDetail)
                            + "\n固定视频域名连接：" + result(result.googleVideo) + detail(result.googleVideoDetail)
                            + "\n\n固定探测地址的 HTTP 状态不代表实际视频是否可播。"
                            + "播放失败后请在手机管理页查看诊断日志。")
                    .setPositiveButton("知道了", null).show();
            say(result.youtube ? "YouTube 检测通过，可返回视频测试播放。" : "YouTube 检测未通过，请查看详细错误。");
        }));
    }

    private static String detail(String value) {
        return value == null || value.isEmpty() ? "" : "（" + value + "）";
    }

    private final class NodeAdapter extends BaseAdapter {
        @Override public int getCount() { return nodeList.size(); }
        @Override public Object getItem(int position) { return nodeList.get(position); }
        @Override public long getItemId(int position) { return nodeList.get(position).runtimeName.hashCode(); }
        @Override public boolean hasStableIds() { return true; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            ProxyNode node = nodeList.get(position);
            TextView row = recycled instanceof TextView ? (TextView) recycled : text("", 17, TEXT);
            row.setText((node.selected ? "✓  " : "     ") + node.name + "\n"
                    + (node.selected ? "使用中 · " : "") + node.type + "  ·  " + delay(node.delayMs));
            row.setTextColor(node.selected ? GREEN : TEXT);
            row.setPadding(dp(16), dp(10), dp(16), dp(10));
            row.setMinHeight(dp(64));
            row.setMaxLines(3);
            return row;
        }
    }
    private boolean visible() { return dialog != null && dialog.isShowing(); }
    private void ui(Runnable action) { main.post(() -> { if (visible()) action.run(); }); }
    private void say(String value) { if (feedback != null) feedback.setText(ProxyErrors.redact(value)); }
    private LinearLayout column() { LinearLayout v = new LinearLayout(context); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(context); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(context); view.setText(value); view.setTextSize(size); view.setTextColor(color); return view;
    }
    private TextView button(String title, Runnable action) {
        TextView view = text(title, 15, TEXT);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(13), dp(10), dp(13), dp(10));
        view.setMinHeight(dp(46));
        view.setFocusable(true); view.setClickable(true);
        view.setBackground(focusBackground());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMargins(0, dp(4), dp(6), dp(4));
        view.setLayoutParams(params);
        view.setOnClickListener(ignored -> action.run());
        return view;
    }
    private StateListDrawable focusBackground() {
        StateListDrawable state = new StateListDrawable();
        state.addState(new int[]{android.R.attr.state_pressed}, shape(BLUE, Color.WHITE));
        state.addState(new int[]{android.R.attr.state_focused}, shape(Color.rgb(35, 75, 121), BLUE));
        state.addState(new int[]{android.R.attr.state_selected}, shape(Color.rgb(35, 75, 121), BLUE));
        state.addState(new int[]{}, shape(CARD, CARD));
        return state;
    }
    private GradientDrawable shape(int fill, int border) {
        GradientDrawable d = new GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(10)); d.setStroke(dp(2), border); return d;
    }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private static String result(boolean ok) { return ok ? "通过" : "未通过"; }
    private static String delay(int value) {
        return value == ProxyNode.DELAY_TESTING ? "测试中…" : value == ProxyNode.DELAY_TIMEOUT ? "超时" : value > 0 ? value + " ms" : "未测速";
    }
    private static boolean validUrl(String value) {
        try { URL u = new URL(value); return ("http".equalsIgnoreCase(u.getProtocol()) || "https".equalsIgnoreCase(u.getProtocol())) && !u.getHost().isEmpty(); }
        catch (Exception ignored) { return false; }
    }
    private static String masked(String value) {
        try { URL u = new URL(value); return u.getProtocol() + "://" + u.getHost() + "/…"; }
        catch (Exception ignored) { return "地址已隐藏"; }
    }
}
