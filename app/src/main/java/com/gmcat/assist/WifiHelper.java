package com.gmcat.assist;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Build;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** WiFi 相关：扫频确认 5G 是否在、以及调起系统添加网络弹窗。 */
public final class WifiHelper {

    public static class Match {
        public final String ssid;
        public final int level;

        Match(String ssid, int level) {
            this.ssid = ssid;
            this.level = level;
        }
    }

    /** 扫描 WiFi 所需的运行时权限。Android 13 起换成附近设备权限。 */
    public static String permission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return Manifest.permission.NEARBY_WIFI_DEVICES;
        }
        return Manifest.permission.ACCESS_FINE_LOCATION;
    }

    /** Android 12 及以下，扫描 WiFi 必须打开系统定位开关，否则拿不到列表。 */
    public static boolean locationEnabled(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return true;
        }
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return lm.isLocationEnabled();
        }
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    public static WifiManager wifi(Context ctx) {
        return (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    /** 归一化：小写 + 去掉空格、连字符、下划线，用于宽松比对。 */
    static String norm(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase(Locale.ROOT).replaceAll("[\\s\\-_]", "");
    }

    /** 名字本身看起来就是 5G 频段。 */
    public static boolean looks5g(String ssid) {
        String n = norm(ssid);
        return n.endsWith("5g") || n.endsWith("5ghz");
    }

    /**
     * 在扫描结果里找 baseSsid 对应的 5G 变体。
     * 覆盖 -5G / _5G / 5G / 5GHz 以及大小写差异。
     *
     * @return 找到则返回信号最好的那个，否则 null
     */
    public static Match find5g(Context ctx, String baseSsid) {
        List<ScanResult> results = scan(ctx);
        if (results == null || results.isEmpty()) {
            return null;
        }
        String base = norm(baseSsid);
        if (base.isEmpty()) {
            return null;
        }
        // 如果标签上的名字本身已带 5G，就不用再找变体
        if (base.endsWith("5g") || base.endsWith("5ghz")) {
            return null;
        }
        String want1 = base + "5g";
        String want2 = base + "5ghz";

        Match best = null;
        for (ScanResult r : results) {
            if (r.SSID == null || r.SSID.isEmpty()) {
                continue;
            }
            String n = norm(r.SSID);
            if (n.equals(want1) || n.equals(want2)) {
                if (best == null || r.level > best.level) {
                    best = new Match(r.SSID, r.level);
                }
            }
        }
        return best;
    }

    /** baseSsid 本身是否出现在扫描结果中。扫不到说明可能被改过名字，或光猫不在附近。 */
    public static boolean exists(Context ctx, String baseSsid) {
        List<ScanResult> results = scan(ctx);
        if (results == null) {
            return false;
        }
        String base = norm(baseSsid);
        for (ScanResult r : results) {
            if (r.SSID != null && norm(r.SSID).equals(base)) {
                return true;
            }
        }
        return false;
    }

    public static List<ScanResult> scan(Context ctx) {
        WifiManager wm = wifi(ctx);
        if (wm == null) {
            return null;
        }
        try {
            if (!wm.isWifiEnabled()) {
                return null;
            }
            return wm.getScanResults();
        } catch (SecurityException e) {
            return null;
        }
    }

    public static String currentSsid(Context ctx) {
        WifiManager wm = wifi(ctx);
        if (wm == null) {
            return "";
        }
        try {
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) {
                return "";
            }
            String s = info.getSSID();
            if (s == null || "<unknown ssid>".equals(s)) {
                return "";
            }
            if (s.length() > 1 && s.startsWith("\"") && s.endsWith("\"")) {
                s = s.substring(1, s.length() - 1);
            }
            return s;
        } catch (SecurityException e) {
            return "";
        }
    }

    /**
     * 系统「添加 WiFi 网络」弹窗，SSID 和密码预填好，用户点一下就连上系统 WiFi。
     * 注意：这是 Android 10+ 唯一能让第三方应用真正把网络写进系统的方式。
     */
    public static Intent addNetworkIntent(String ssid, String password) {
        WifiNetworkSuggestion.Builder builder = new WifiNetworkSuggestion.Builder().setSsid(ssid);
        if (password != null && !password.isEmpty()) {
            // WPA/WPA2 要求 8~63 个字符，否则会抛异常
            builder.setWpa2Passphrase(password);
        }
        WifiNetworkSuggestion suggestion = builder.build();

        ArrayList<WifiNetworkSuggestion> list = new ArrayList<>();
        list.add(suggestion);

        Intent intent = new Intent(Settings.ACTION_WIFI_ADD_NETWORKS);
        intent.putExtra(Settings.EXTRA_WIFI_NETWORK_LIST, list);
        return intent;
    }

    /** 密码长度是否符合 WPA 要求，用于提前给出友好提示而不是抛异常。 */
    public static boolean passphraseValid(String pwd) {
        if (pwd == null) {
            return false;
        }
        int len = pwd.length();
        return len >= 8 && len <= 63;
    }

    private WifiHelper() {
    }
}
