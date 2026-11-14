package com.gmcat.assist;

import android.content.Context;
import android.content.SharedPreferences;

/** 极简配置：接口地址 / Key / 模型名。不保存任何光猫信息。 */
public final class Prefs {

    private static final String NAME = "gmcat_cfg";

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static String baseUrl(Context c) {
        return sp(c).getString("baseUrl", "https://ark.cn-beijing.volces.com/api/v3");
    }

    public static String apiKey(Context c) {
        return sp(c).getString("apiKey", "");
    }

    public static String model(Context c) {
        return sp(c).getString("model", "");
    }

    /** 测速页地址，和「进后台」一样用系统浏览器打开。 */
    public static String speedUrl(Context c) {
        return sp(c).getString("speedUrl", "https://www.speedtest.cn/");
    }

    public static void save(Context c, String baseUrl, String key, String model, String speedUrl) {
        sp(c).edit()
                .putString("baseUrl", baseUrl.trim())
                .putString("apiKey", key.trim())
                .putString("model", model.trim())
                .putString("speedUrl", speedUrl.trim())
                .apply();
    }

    public static boolean ready(Context c) {
        return !apiKey(c).trim().isEmpty() && !model(c).trim().isEmpty();
    }

    private Prefs() {
    }
}
