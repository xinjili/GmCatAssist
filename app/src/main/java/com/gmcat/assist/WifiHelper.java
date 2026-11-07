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

    /**
     * 归一化：全角转半角 + 小写 + 去掉空格、连字符、下划线。
     * 用于宽松比对，避免因为 -5G / _5G / 全角连字符这类差异匹配不上。
     */
    static String norm(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '\uFF01' && c <= '\uFF5E') {
                // 全角 ASCII 转半角
                c = (char) (c - 0xFEE0);
            } else if (c == '\u3000') {
                c = ' ';
            }
            sb.append(c);
        }
        return sb.toString().toLowerCase(Locale.ROOT).replaceAll("[\\s\\-_\u2010-\u2015]", "");
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
        return has(scanNow(ctx), baseSsid);
    }

    /**
     * 触发一次扫描并返回结果。
     *
     * <p>getScanResults 只返回系统上一次扫描的缓存，缓存为空时（刚开机、刚开 WiFi、
     * 很久没扫过）会直接拿到空列表，容易被误判成「确实没有这个网络」。
     * 所以缓存为空时主动 startScan 一次，等系统回填。
     *
     * <p>返回 null 表示扫描不可用（无权限 / WiFi 未开 / 系统拒绝），
     * 与「扫到了但里面没有目标网络」是两回事，调用方要区分处理。
     *
     * <p>内部有等待，必须在后台线程调用。
     */
    public static List<ScanResult> scanNow(Context ctx) {
        WifiManager wm = wifi(ctx);
        if (wm == null) {
            return null;
        }
        try {
            if (!wm.isWifiEnabled()) {
                return null;
            }
            // 始终触发一次新扫描。缓存可能来自几分钟前（光猫还没开机时），
            // 直接信任它会漏掉刚开机的网络。
            wm.startScan();
            Thread.sleep(2500);
            List<ScanResult> results = wm.getScanResults();
            if (results != null && !results.isEmpty()) {
                return results;
            }
            // 还没结果就再等一轮
            Thread.sleep(1500);
            return wm.getScanResults();
        } catch (SecurityException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * 找与目标最相似、但不完全相同的 SSID。
     * 用于诊断「识别出来的名字和实际名字差一两个字符」的情况。
     *
     * @param maxDist 允许的最大编辑距离，超过则认为不相似
     * @return 最相似的 SSID，没有则 null
     */
    public static String nearest(List<ScanResult> results, String target, int maxDist) {
        if (results == null || target == null || target.isEmpty()) {
            return null;
        }
        String t = norm(target);
        String best = null;
        int bestDist = maxDist + 1;
        for (ScanResult r : results) {
            if (r.SSID == null || r.SSID.isEmpty()) {
                continue;
            }
            String n = norm(r.SSID);
            if (n.equals(t)) {
                continue;
            }
            int d = editDistance(t, n);
            if (d < bestDist) {
                bestDist = d;
                best = r.SSID;
            }
        }
        return bestDist <= maxDist ? best : null;
    }

    /** Levenshtein 距离，SSID 一般十几位，直接算即可。 */
    private static int editDistance(String a, String b) {
        int la = a.length();
        int lb = b.length();
        if (la == 0) {
            return lb;
        }
        if (lb == 0) {
            return la;
        }
        int[] prev = new int[lb + 1];
        int[] cur = new int[lb + 1];
        for (int j = 0; j <= lb; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= la; i++) {
            cur[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= lb; j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                int del = prev[j] + 1;
                int ins = cur[j - 1] + 1;
                int sub = prev[j - 1] + cost;
                cur[j] = Math.min(Math.min(del, ins), sub);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[lb];
    }

    /** 结果集里是否包含指定 SSID。宽松比对，忽略大小写与 - _ 空格差异。 */
    public static boolean has(List<ScanResult> results, String ssid) {
        if (results == null || ssid == null || ssid.isEmpty()) {
            return false;
        }
        String base = norm(ssid);
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
