package com.liskovsoft.smartyoutubetv2.tv.proxy.model;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

public final class SubscriptionProfile {
    public static final String MODE_AUTO = "AUTO";
    public static final String MODE_MANUAL = "MANUAL";

    public String id;
    public String name;
    public String url;
    public String configPath;
    public long lastUpdateTime;
    public boolean lastUpdateSuccess;
    public String selectedNode;
    public String selectedGroup;
    public String selectedNodeMode;
    public boolean active;
    public int nodeCount;

    public static SubscriptionProfile create(String name, String url) {
        SubscriptionProfile profile = new SubscriptionProfile();
        profile.id = "sub_" + UUID.randomUUID().toString().replace("-", "");
        profile.name = name;
        profile.url = url;
        profile.selectedNodeMode = MODE_AUTO;
        return profile;
    }

    public SubscriptionProfile copy() {
        try {
            return fromJson(toJson());
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", name);
        json.put("url", url);
        json.put("configPath", configPath);
        json.put("lastUpdateTime", lastUpdateTime);
        json.put("lastUpdateSuccess", lastUpdateSuccess);
        json.put("selectedNode", selectedNode);
        json.put("selectedGroup", selectedGroup);
        json.put("selectedNodeMode", selectedNodeMode);
        json.put("active", active);
        json.put("nodeCount", nodeCount);
        return json;
    }

    public static SubscriptionProfile fromJson(JSONObject json) {
        SubscriptionProfile profile = new SubscriptionProfile();
        profile.id = json.optString("id", "");
        profile.name = json.optString("name", "");
        profile.url = json.optString("url", "");
        profile.configPath = nullable(json, "configPath");
        profile.lastUpdateTime = json.optLong("lastUpdateTime", 0L);
        profile.lastUpdateSuccess = json.optBoolean("lastUpdateSuccess", false);
        profile.selectedNode = nullable(json, "selectedNode");
        profile.selectedGroup = nullable(json, "selectedGroup");
        profile.selectedNodeMode = json.optString("selectedNodeMode", MODE_AUTO);
        profile.active = json.optBoolean("active", false);
        profile.nodeCount = json.optInt("nodeCount", 0);
        return profile;
    }

    private static String nullable(JSONObject json, String key) {
        return json.isNull(key) ? null : json.optString(key, null);
    }
}
