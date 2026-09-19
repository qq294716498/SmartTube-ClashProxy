package com.liskovsoft.smartyoutubetv2.tv.proxy.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.appcompat.app.AlertDialog;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.tv.proxy.MihomoCoreManager;
import com.liskovsoft.smartyoutubetv2.tv.proxy.ProxyRuntimeCoordinator;
import com.liskovsoft.smartyoutubetv2.tv.proxy.data.ProxyHealthChecker;
import com.liskovsoft.smartyoutubetv2.tv.proxy.data.ProxyNodeManager;
import com.liskovsoft.smartyoutubetv2.tv.proxy.data.ProxyPreferences;
import com.liskovsoft.smartyoutubetv2.tv.proxy.data.SubscriptionManager;
import com.liskovsoft.smartyoutubetv2.tv.proxy.model.ProxyNode;
import com.liskovsoft.smartyoutubetv2.tv.proxy.model.SubscriptionProfile;

import java.net.MalformedURLException;
import java.net.URL;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** SmartTube-native TV dialogs for embedded proxy management. */
public final class ProxySettingsPresenter {
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SubscriptionManager subscriptions;
    private final ProxyNodeManager nodes;
    private final ProxyPreferences preferences;

    private ProxySettingsPresenter(Context context) {
        this.context = context;
        subscriptions = new SubscriptionManager(context);
        nodes = new ProxyNodeManager(subscriptions);
        preferences = new ProxyPreferences(context);
    }

    public static void show(Context context) {
        new ProxySettingsPresenter(context).showMain();
    }

    private void showMain() {
        AppDialogPresenter dialog = AppDialogPresenter.instance(context);
        SubscriptionProfile active = subscriptions.getActive();
        boolean enabled = preferences.isEnabled();

        String status = !enabled ? "未启用" : active == null ? "未连接"
                : MihomoCoreManager.getState() == MihomoCoreManager.State.RUNNING ? "已连接" : "正在连接";
        dialog.appendSingleButton(UiOptionItem.from("代理状态", "● " + status, item -> { }));
        dialog.appendSingleSwitch(UiOptionItem.from("使用内置代理",
                option -> setEnabled(option.isSelected()), enabled));
        dialog.appendSingleButton(UiOptionItem.from("Mihomo 初始化自检",
                MihomoCoreManager.getStartupDiagnostic(context),
                item -> runMihomoSelfTest()));
        dialog.appendSingleButton(UiOptionItem.from("导出 Mihomo 日志",
                "保存到手机“下载/SmartTube-Proxy”",
                item -> exportMihomoLog()));

        dialog.appendSingleButton(UiOptionItem.from("当前订阅",
                active == null ? "尚未添加订阅" : active.name,
                item -> navigateTo(this::showSubscriptions)));
        dialog.appendSingleButton(UiOptionItem.from("当前节点",
                active == null || active.selectedNode == null ? "未选择" : active.selectedNode,
                item -> showNodes(active)));
        dialog.appendSingleButton(UiOptionItem.from("订阅管理",
                subscriptions.list().size() + " 个订阅",
                item -> navigateTo(this::showSubscriptions)));
        dialog.appendSingleButton(UiOptionItem.from("重新连接", item -> reconnect()));
        dialog.appendSingleButton(UiOptionItem.from("代理诊断", item -> runDiagnostics()));
        dialog.showDialog("网络代理");
    }

    /**
     * Replace the contents of the currently visible TV dialog after the
     * current key/click dispatch has completed. Closing the dialog host before
     * opening the next page exposes the underlying settings activity and can
     * leave a non-interactive dialog window on Android 16.
     */
    private void navigateTo(Runnable page) {
        main.post(page);
    }

    private void setEnabled(boolean enabled) {
        MessageHelpers.showMessage(context, enabled ? "正在连接..." : "正在关闭代理...");
        ProxyRuntimeCoordinator.setEnabled(context, enabled, error -> main.post(() -> {
            if (error != null) {
                MessageHelpers.showLongMessage(context, error);
            }
            navigateTo(this::showMain);
        }));
    }

    private void runMihomoSelfTest() {
        MihomoCoreManager.installDiagnosticCrashHandler(context);
        MihomoCoreManager.State state = MihomoCoreManager.getState();
        if (state == MihomoCoreManager.State.RUNNING) {
            MessageHelpers.showMessage(context, "Mihomo 已初始化成功");
            navigateTo(this::showMain);
            return;
        }
        if (state == MihomoCoreManager.State.FAILED) {
            MessageHelpers.showLongMessage(context,
                    "本次进程已经测试失败，请先导出日志；重启 App 后可再次测试");
            navigateTo(this::showMain);
            return;
        }

        MessageHelpers.showMessage(context, "正在执行 Mihomo 初始化自检...");
        MihomoCoreManager.start(context);
        pollMihomoSelfTest(System.currentTimeMillis() + 12_000);
    }

    private void pollMihomoSelfTest(long deadline) {
        MihomoCoreManager.State state = MihomoCoreManager.getState();
        if (state == MihomoCoreManager.State.STARTING && System.currentTimeMillis() < deadline) {
            main.postDelayed(() -> pollMihomoSelfTest(deadline), 250);
            return;
        }

        String stage = MihomoCoreManager.getStartupDiagnostic(context);
        if (state == MihomoCoreManager.State.RUNNING) {
            MessageHelpers.showLongMessage(context, "自检成功：" + stage);
        } else {
            MessageHelpers.showLongMessage(context,
                    "自检未通过：" + stage + "。请点击“导出 Mihomo 日志”");
        }
        navigateTo(this::showMain);
    }

    private void exportMihomoLog() {
        String result = MihomoCoreManager.exportDiagnosticLog(context);
        MessageHelpers.showLongMessage(context,
                result.startsWith("下载/") ? "日志已保存到：" + result : result);
    }

    private void reconnect() {
        SubscriptionProfile active = subscriptions.getActive();
        if (active == null) {
            MessageHelpers.showMessage(context, "尚未添加订阅");
            showSubscriptionForm(null);
            return;
        }
        MessageHelpers.showMessage(context, "正在重新连接...");
        subscriptions.activate(active.id, (profile, error) -> main.post(() -> {
            MessageHelpers.showMessage(context, error == null ? "重新连接成功" : "重新连接失败");
            navigateTo(this::showMain);
        }));
    }

    private void showSubscriptions() {
        AppDialogPresenter dialog = AppDialogPresenter.instance(context);
        List<SubscriptionProfile> profiles = subscriptions.list();
        for (SubscriptionProfile profile : profiles) {
            String marker = profile.active ? "● " : "○ ";
            String update = profile.lastUpdateTime == 0 ? "尚未更新"
                    : profile.lastUpdateSuccess ? "已更新 · " + formatTime(profile.lastUpdateTime)
                    : "更新失败 · 正在使用本地缓存";
            String description = update + " · " + profile.nodeCount + " 个节点";
            dialog.appendSingleButton(UiOptionItem.from(marker + profile.name, description,
                    item -> navigateTo(() -> showSubscriptionDetails(profile.id))));
        }
        dialog.appendSingleButton(UiOptionItem.from("+ 添加订阅", item -> showSubscriptionForm(null)));
        dialog.showDialog("订阅管理");
    }

    private void showSubscriptionDetails(String id) {
        SubscriptionProfile profile = subscriptions.get(id);
        if (profile == null) {
            navigateTo(this::showSubscriptions);
            return;
        }
        AppDialogPresenter dialog = AppDialogPresenter.instance(context);
        String state = profile.configPath != null && new java.io.File(profile.configPath).isFile()
                ? profile.lastUpdateSuccess ? "● 正常" : "● 使用本地缓存" : "○ 不可用";
        dialog.appendSingleButton(UiOptionItem.from("状态", state, item -> { }));
        dialog.appendSingleButton(UiOptionItem.from("订阅地址", maskUrl(profile.url), item -> { }));
        dialog.appendSingleButton(UiOptionItem.from("节点数量", profile.nodeCount + "", item -> { }));
        dialog.appendSingleButton(UiOptionItem.from("最后更新",
                profile.lastUpdateTime == 0 ? "从未" : formatTime(profile.lastUpdateTime), item -> { }));
        dialog.appendSingleButton(UiOptionItem.from("当前节点",
                profile.selectedNode == null ? "未选择" : profile.selectedNode, item -> { }));
        dialog.appendSingleButton(UiOptionItem.from("节点选择", item -> showNodes(profile)));
        dialog.appendSingleButton(UiOptionItem.from(profile.active ? "当前正在使用" : "切换到此订阅",
                item -> activate(profile)));
        dialog.appendSingleButton(UiOptionItem.from("更新订阅", item -> update(profile)));
        dialog.appendSingleButton(UiOptionItem.from("编辑订阅", item -> showSubscriptionForm(profile)));
        dialog.appendSingleButton(UiOptionItem.from("删除订阅", item -> confirmDelete(profile)));
        dialog.showDialog(profile.name);
    }

    private void activate(SubscriptionProfile profile) {
        MessageHelpers.showMessage(context, "正在切换...");
        subscriptions.activate(profile.id, (updated, error) -> main.post(() -> {
            MessageHelpers.showMessage(context, error == null ? "订阅切换成功" : "订阅切换失败");
            navigateTo(() -> showSubscriptionDetails(profile.id));
        }));
    }

    private void update(SubscriptionProfile profile) {
        MessageHelpers.showMessage(context, "正在更新订阅...");
        subscriptions.update(profile.id, (updated, error) -> main.post(() -> {
            if (error == null) {
                MessageHelpers.showMessage(context, "订阅更新成功");
            } else if (updated != null && updated.configPath != null && new java.io.File(updated.configPath).isFile()) {
                MessageHelpers.showLongMessage(context, "订阅更新失败，正在使用上次配置");
            } else {
                MessageHelpers.showLongMessage(context, "订阅不可用");
            }
            navigateTo(() -> showSubscriptionDetails(profile.id));
        }));
    }

    private void showSubscriptionForm(SubscriptionProfile existing) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * context.getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding / 2, padding, 0);

        EditText name = new EditText(context);
        name.setHint("名称（留空自动生成）");
        name.setSingleLine(true);
        name.setText(existing == null ? "" : existing.name);
        layout.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        EditText url = new EditText(context);
        url.setHint("HTTPS 订阅地址");
        url.setSingleLine(true);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(existing == null ? "" : existing.url);
        layout.addView(url, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog form = new AlertDialog.Builder(context)
                .setTitle(existing == null ? "添加订阅" : "编辑订阅")
                .setView(layout)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存并更新", null)
                .create();
        form.setOnShowListener(ignored -> {
            form.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(button -> {
                String enteredUrl = url.getText().toString().trim();
                if (!isHttpsUrl(enteredUrl)) {
                    url.setError("请输入有效的 HTTPS 订阅地址");
                    url.requestFocus();
                    return;
                }
                boolean urlChanged = existing == null || !enteredUrl.equals(existing.url);
                SubscriptionProfile saved = existing == null
                        ? subscriptions.add(name.getText().toString(), enteredUrl)
                        : subscriptions.edit(existing.id, name.getText().toString(), enteredUrl);
                form.dismiss();
                if (saved != null && urlChanged) {
                    update(saved);
                } else if (saved != null) {
                    navigateTo(() -> showSubscriptionDetails(saved.id));
                }
            });
        });
        form.show();
        name.requestFocus();
    }

    private void confirmDelete(SubscriptionProfile profile) {
        new AlertDialog.Builder(context)
                .setTitle("删除“" + profile.name + "”？")
                .setMessage("此操作会删除该订阅本地配置。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    subscriptions.delete(profile.id);
                    MessageHelpers.showMessage(context, "订阅已删除");
                    navigateTo(this::showSubscriptions);
                })
                .show();
    }

    private void showNodes(SubscriptionProfile profile) {
        if (profile == null || !profile.active) {
            MessageHelpers.showMessage(context, profile == null ? "尚未添加订阅" : "请先切换到此订阅");
            return;
        }
        if (!preferences.isEnabled()) {
            MessageHelpers.showMessage(context, "请先开启内置代理");
            return;
        }
        MessageHelpers.showMessage(context, "正在读取节点...");
        nodes.query(profile, (group, list, error) -> main.post(() -> {
            if (error != null) {
                MessageHelpers.showLongMessage(context, "节点读取失败");
                return;
            }
            navigateTo(() -> showNodeList(profile, group, list));
        }));
    }

    private void showNodeList(SubscriptionProfile profile, String group, List<ProxyNode> list) {
        AppDialogPresenter dialog = AppDialogPresenter.instance(context);
        List<OptionItem> options = new ArrayList<>();
        for (ProxyNode node : list) {
            options.add(UiOptionItem.from(node.name, delayText(node.delayMs), option -> {
                ProxyNode selected = (ProxyNode) option.getData();
                nodes.switchNode(profile, group, selected, list.size(), error -> main.post(() -> {
                    MessageHelpers.showMessage(context,
                            error == null ? "已切换到 " + selected.name : "节点切换失败，请重新选择");
                }));
            }, node.selected, node));
        }
        dialog.appendRadioCategory("可用节点", options);
        dialog.appendSingleButton(UiOptionItem.from("测试全部节点", item -> {
            MessageHelpers.showMessage(context, "正在测速 0 / " + list.size());
            nodes.testAll(profile, list, new ProxyNodeManager.DelayProgress() {
                @Override
                public void onProgress(int completed, int total, ProxyNode node) {
                    main.post(() -> MessageHelpers.showMessage(context,
                            "正在测速 " + completed + " / " + total + " · " + node.name + " " + delayText(node.delayMs)));
                }

                @Override
                public void onComplete() {
                    main.post(() -> MessageHelpers.showMessage(context,
                            "测速完成，重新进入节点页面可查看结果"));
                }
            });
        }));
        dialog.showDialog("节点选择");
    }

    private void runDiagnostics() {
        MessageHelpers.showMessage(context, "正在执行代理诊断...");
        ProxyHealthChecker.check(result -> main.post(
                () -> navigateTo(() -> showDiagnostics(result))));
    }

    private void showDiagnostics(ProxyHealthChecker.Result health) {
        SubscriptionProfile active = subscriptions.getActive();
        AppDialogPresenter dialog = AppDialogPresenter.instance(context);
        String core = MihomoCoreManager.getState().name();
        dialog.appendSingleButton(UiOptionItem.from("Mihomo Core", core, item -> { }));
        dialog.appendSingleButton(UiOptionItem.from("Local Proxy", "127.0.0.1:7890", item -> { }));
        dialog.appendSingleButton(UiOptionItem.from("Current Subscription",
                active == null ? "None" : active.name, item -> { }));
        dialog.appendSingleButton(UiOptionItem.from("Config",
                active != null && active.configPath != null && new java.io.File(active.configPath).isFile()
                        ? "Loaded" : "Failed", item -> { }));
        dialog.appendSingleButton(UiOptionItem.from("Selected Node",
                active == null || active.selectedNode == null ? "None" : active.selectedNode, item -> { }));
        dialog.appendSingleButton(UiOptionItem.from("Local Proxy Check", status(health.localProxy), item -> { }));
        dialog.appendSingleButton(UiOptionItem.from("YouTube", status(health.youtube), item -> { }));
        dialog.appendSingleButton(UiOptionItem.from("GoogleVideo", status(health.googleVideo), item -> { }));
        dialog.appendSingleButton(UiOptionItem.from("DNS", status(health.dns), item -> { }));
        dialog.showDialog("代理诊断");
    }

    private static String status(boolean success) {
        return success ? "OK" : "Failed";
    }

    private static String delayText(int delay) {
        if (delay == ProxyNode.DELAY_TESTING) {
            return "测试中...";
        }
        if (delay == ProxyNode.DELAY_TIMEOUT) {
            return "超时";
        }
        return delay > 0 ? delay + " ms" : "未测速";
    }

    private static boolean isHttpsUrl(String value) {
        try {
            URL url = new URL(value);
            return "https".equalsIgnoreCase(url.getProtocol()) && url.getHost() != null && !url.getHost().isEmpty();
        } catch (MalformedURLException ignored) {
            return false;
        }
    }

    private static String maskUrl(String value) {
        if (value == null || value.isEmpty()) {
            return "未设置";
        }
        try {
            URL url = new URL(value);
            return url.getProtocol() + "://" + url.getHost() + "/****";
        } catch (MalformedURLException ignored) {
            return "****";
        }
    }

    private static String formatTime(long time) {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(time));
    }
}
