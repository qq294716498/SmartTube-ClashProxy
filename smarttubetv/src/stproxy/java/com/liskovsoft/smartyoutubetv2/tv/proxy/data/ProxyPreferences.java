package com.liskovsoft.smartyoutubetv2.tv.proxy.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.liskovsoft.smartyoutubetv2.tv.proxy.model.SubscriptionProfile;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

public final class ProxyPreferences {
    private static final String PREFS = "smarttube_embedded_proxy";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_ACTIVE_ID = "active_subscription_id";
    private static final String KEY_PROFILES = "subscription_profiles";

    private final SharedPreferences preferences;

    public ProxyPreferences(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isEnabled() {
        return preferences.getBoolean(KEY_ENABLED, false);
    }

    public void setEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public String getActiveId() {
        return preferences.getString(KEY_ACTIVE_ID, null);
    }

    public synchronized List<SubscriptionProfile> loadProfiles() {
        List<SubscriptionProfile> profiles = new ArrayList<>();
        String value = preferences.getString(KEY_PROFILES, "[]");
        try {
            JSONArray array = new JSONArray(value);
            for (int index = 0; index < array.length(); index++) {
                SubscriptionProfile profile = SubscriptionProfile.fromJson(array.getJSONObject(index));
                if (!profile.id.isEmpty()) {
                    profiles.add(profile);
                }
            }
        } catch (JSONException ignored) {
            // Corrupt metadata must not prevent SmartTube from starting.
        }
        return profiles;
    }

    public synchronized void saveProfiles(List<SubscriptionProfile> profiles, String activeId) {
        JSONArray array = new JSONArray();
        for (SubscriptionProfile profile : profiles) {
            try {
                profile.active = profile.id.equals(activeId);
                array.put(profile.toJson());
            } catch (JSONException ignored) {
                // Skip only the malformed record; keep every other subscription.
            }
        }
        preferences.edit()
                .putString(KEY_PROFILES, array.toString())
                .putString(KEY_ACTIVE_ID, activeId)
                .apply();
    }
}
