package com.gmcat.assist;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 调 OpenAI 兼容的视觉接口，把光猫标签转成结构化字段。 */
public final class VisionClient {

    /** 识别结果。空字符串表示标签上没有/没认出来。 */
    public static class Result {
        public String ssid5g = "";
        public String pwd5g = "";
        public String ssid24g = "";
        public String pwd24g = "";

        public String adminUrl = "";
        public String adminUser = "";
        public String adminPassword = "";

        public String sn = "";
        public String mac = "";
        public String model = "";
        public String extra = "";

        public String confidence = "";
        public String note = "";

        /** 标签上是否印了独立的 5G 无线名称。双频合一的设备只有一个名字。 */
        public boolean hasSeparate5g = true;

        /** 至少有一组 SSID + 密码可用。 */
        public boolean usable() {
            boolean a = !ssid5g.isEmpty() && !pwd5g.isEmpty();
            boolean b = !ssid24g.isEmpty() && !pwd24g.isEmpty();
            return a || b;
        }
    }

    private static final String PROMPT =
            "你是运营商光猫（宽带调制解调器 / 智能家庭网关）标签识别助手。图片是设备背面或侧面的贴纸。\n"
            + "只输出一个 JSON 对象，不要任何解释文字，不要 markdown 代码块。\n\n"
            + "【重要前提】不同品牌、不同运营商的标签排版差异极大：华为、中兴、烽火、吉比特、天邑、\n"
            + "创维、九联、华勤、诺基亚贝尔等，字段名、排列方式、中英文、有无条形码都不相同，\n"
            + "也可能出现竖排、表格、多列、图文混排。请按信息的**含义**去找，\n"
            + "不要依赖固定字段名、固定位置或固定排版。\n\n"
            + "需要提取的内容：\n\n"
            + "1) 无线网络名称（SSID / WLAN名称 / WiFi名称 / 默认无线网络名称 / 无线网络标识 等）\n"
            + "   - 2.4G 频段的名称放在 ssid_24g\n"
            + "   - 5G 频段的名称放在 ssid_5g\n"
            + "   - 若用 SSID-1 / SSID-5 / 2.4G / 5G 等编号区分，按「名称里是否含 5G」判断归属；\n"
            + "     编号里的数字本身不代表频段，不要按数字猜。\n"
            + "   - 若标签只印了一个无线名称，填进 ssid_24g，ssid_5g 留空，并把 has_separate_5g 设为 false。\n\n"
            + "2) 无线密码（WLAN密码 / 无线网络密钥 / WiFi密码 / WPA密钥 / 预共享密钥 等）\n"
            + "   - 2.4G 对应 pwd_24g，5G 对应 pwd_5g\n"
            + "   - 两个频段共用一个密码时，两个字段填同一个值；只印一个密码时，两个都填它\n\n"
            + "3) 管理后台\n"
            + "   - admin_url：管理地址 / 默认终端配置地址 / 登录IP，只填 IP 或域名\n"
            + "   - admin_user：管理账号 / 用户名（可能是 user、useradmin、CMCCAdmin、admin 等）\n"
            + "   - admin_password：管理密码\n\n"
            + "4) 设备信息\n"
            + "   - sn：SN / 序列号 / 设备标识 / PON SN / 条码号，完整原样抄写，含字母前缀，不要截断\n"
            + "     华为光猫的 SN 以 HWTC 开头，也常印成十六进制 48575443 开头的一串\n"
            + "   - mac：MAC 地址\n"
            + "   - model：设备型号 / 产品型号 / 设备类型\n"
            + "   - extra：其他有用信息（PON SN、生产日期、厂商、批次等），写成「名称: 值」，多条用分号分隔\n\n"
            + "5) 元信息\n"
            + "   - has_separate_5g：标签上是否印了独立的 5G 无线名称（true / false）\n"
            + "   - confidence：high / medium / low\n"
            + "   - notes：缺失、模糊、有歧义、存在多个候选等情况，简短说明；否则空字符串\n\n"
            + "规则：\n"
            + "1. 严格照抄，绝不推测、绝不编造。看不清的字段填空字符串。\n"
            + "2. **不要因为字段名没见过就留空**，按含义判断它是什么。\n"
            + "3. 无线名称原样抄写，不要自行添加或删除任何字符。\n"
            + "4. 若密码中间被排版空格或连字符分组（如 AB12 CD34），去掉分组符号输出连续字符。\n"
            + "5. 若一行里有括号注释，如 PON SN: HWTC1D8B11AF(48575443 1D8B11AF)，取括号外的主值。\n"
            + "6. 特别注意区分容易混淆的字符：0 与 O、1 与 l 与 I、5 与 S、2 与 Z。\n"
            + "7. 只要标签上有 SN，就必须填进 sn 字段，绝不能塞进 extra。\n"
            + "8. 同一类信息出现多组候选时，取最可能是「出厂默认配置」的那组，其余写进 notes。\n"
            + "9. 只输出 JSON，不要输出其他任何内容。\n\n"
            + "输出的 JSON 结构：\n"
            + "{\"ssid_5g\":\"\",\"pwd_5g\":\"\",\"ssid_24g\":\"\",\"pwd_24g\":\"\","
            + "\"admin_url\":\"\",\"admin_user\":\"\",\"admin_password\":\"\","
            + "\"sn\":\"\",\"mac\":\"\",\"model\":\"\",\"extra\":\"\","
            + "\"has_separate_5g\":true,\"confidence\":\"\",\"notes\":\"\"}";

    public static Result recognize(Context ctx, Uri imageUri) throws Exception {
        byte[] jpeg = loadAndCompress(ctx, imageUri, 1600);
        String b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP);
        try {
            return callApi(ctx, b64, true);
        } catch (Exception e) {
            // 少数兼容服务不认 thinking 字段，去掉再试一次
            String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            boolean fieldRejected = m.contains("thinking") || m.contains("reasoning")
                    || m.contains("unknown") || m.contains("extra field");
            if (fieldRejected) {
                return callApi(ctx, b64, false);
            }
            throw e;
        }
    }

    private static Result callApi(Context ctx, String base64, boolean disableThinking)
            throws Exception {
        String url = endpoint(Prefs.baseUrl(ctx));
        String key = Prefs.apiKey(ctx);
        String model = Prefs.model(ctx);
        if (key.isEmpty() || model.isEmpty()) {
            throw new IOException("识别接口未配置，请先去设置里填 Key 和模型名");
        }

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("temperature", 0);
        // 输出只是一小段 JSON，给足余量即可，避免模型话痨
        body.put("max_tokens", 800);
        if (disableThinking) {
            // 关掉思维链。识标签是纯字段提取，思考纯属浪费时间。
            // 用官方文档明确支持的写法，不要额外塞 reasoning_effort，
            // 那个字段只接受 low/high/max，传 none 可能直接 400。
            JSONObject thinking = new JSONObject();
            thinking.put("type", "disabled");
            body.put("thinking", thinking);
        }

        JSONArray content = new JSONArray();

        JSONObject textPart = new JSONObject();
        textPart.put("type", "text");
        textPart.put("text", PROMPT);
        content.put(textPart);

        JSONObject imagePart = new JSONObject();
        imagePart.put("type", "image_url");
        JSONObject imageUrl = new JSONObject();
        imageUrl.put("url", "data:image/jpeg;base64," + base64);
        imagePart.put("image_url", imageUrl);
        content.put(imagePart);

        JSONObject message = new JSONObject();
        message.put("role", "user");
        message.put("content", content);

        JSONArray messages = new JSONArray();
        messages.put(message);
        body.put("messages", messages);

        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Authorization", "Bearer " + key);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(90000);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(payload.length);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                String err = readAll(conn.getErrorStream());
                throw new IOException("识别接口返回 " + code + "：" + brief(err));
            }

            String resp = readAll(conn.getInputStream());
            String contentStr = extractContent(resp);
            return parse(contentStr);
        } finally {
            conn.disconnect();
        }
    }

    private static String extractContent(String resp) throws Exception {
        JSONObject root = new JSONObject(resp);
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new IOException("识别结果为空");
        }
        JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
        if (msg == null) {
            throw new IOException("识别结果格式异常");
        }
        Object c = msg.opt("content");
        if (c instanceof String) {
            return (String) c;
        }
        // 少数实现返回分段数组
        if (c instanceof JSONArray) {
            StringBuilder sb = new StringBuilder();
            JSONArray arr = (JSONArray) c;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject seg = arr.optJSONObject(i);
                if (seg != null && seg.has("text")) {
                    sb.append(seg.optString("text"));
                }
            }
            return sb.toString();
        }
        return "";
    }

    private static Result parse(String raw) throws Exception {
        String s = raw == null ? "" : raw.trim();
        // 去掉 ```json 包裹
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) {
                s = s.substring(nl + 1);
            }
            int fence = s.lastIndexOf("```");
            if (fence >= 0) {
                s = s.substring(0, fence);
            }
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IOException("没有解析到结构化结果，请重拍标签");
        }
        s = s.substring(start, end + 1);

        JSONObject o = new JSONObject(s);
        Result r = new Result();
        r.ssid5g = clean(o.optString("ssid_5g"));
        r.pwd5g = clean(o.optString("pwd_5g"));
        r.ssid24g = clean(o.optString("ssid_24g"));
        r.pwd24g = clean(o.optString("pwd_24g"));

        r.adminUrl = normalizeUrl(clean(o.optString("admin_url")));
        r.adminUser = clean(o.optString("admin_user"));
        r.adminPassword = clean(o.optString("admin_password"));

        r.sn = clean(o.optString("sn"));
        r.mac = clean(o.optString("mac"));
        r.model = clean(o.optString("model"));
        r.extra = clean(o.optString("extra"));

        r.confidence = clean(o.optString("confidence")).toLowerCase();
        r.note = clean(o.optString("notes"));
        r.hasSeparate5g = o.optBoolean("has_separate_5g", true);

        deriveBands(r);
        normalizeSn(r);
        return r;
    }

    /** 华为光猫 SN：HWTC 字面前缀，或等价的十六进制写法 48575443 前缀（标准 8 位后接）。 */
    private static final Pattern HUAWEI_SN = Pattern.compile(
            "(?i)(HWTC[0-9A-Z]{8}(?![0-9A-Z])|48575443[0-9A-F]{8}(?![0-9A-F]))");

    /** 位数被认错时的宽松兜底。 */
    private static final Pattern HUAWEI_SN_LOOSE = Pattern.compile(
            "(?i)(HWTC[0-9A-Z]{6,20}|48575443[0-9A-F]{6,20})");

    /** 通用写法：SN: xxx / 序列号：xxx */
    private static final Pattern LABELED_SN = Pattern.compile(
            "(?i)(?:SN|序列号|设备标识|条码号?)\\s*[:：]\\s*([A-Za-z0-9\\-]{6,32})");

    /**
     * SN 是后台认证的关键项，不能让它掉进 extra 里。
     * 模型漏填时，从 extra 中按华为规则和通用写法兜底捞出来。
     */
    private static void normalizeSn(Result r) {
        if (!r.sn.isEmpty()) {
            String found = findHuaweiSn(r.sn);
            if (found != null) {
                r.sn = found;
            }
            return;
        }
        if (r.extra.isEmpty()) {
            return;
        }
        String found = findHuaweiSn(r.extra);
        if (found != null) {
            r.sn = found;
            stripFromExtra(r, found);
            return;
        }
        Matcher gen = LABELED_SN.matcher(r.extra);
        if (gen.find()) {
            r.sn = gen.group(1);
            stripFromExtra(r, gen.group(0));
        }
    }

    /** 先按标准位数找，找不到再放宽，容忍 OCR 多认或少认一位。 */
    private static String findHuaweiSn(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Matcher strict = HUAWEI_SN.matcher(text);
        if (strict.find()) {
            return strict.group(1);
        }
        Matcher loose = HUAWEI_SN_LOOSE.matcher(text);
        if (loose.find()) {
            return loose.group(1);
        }
        return null;
    }

    /** 从 extra 里抹掉已经提到 sn 的内容，避免重复展示。 */
    private static void stripFromExtra(Result r, String matched) {
        String s = r.extra == null ? "" : r.extra;
        s = s.replace(matched, "");
        s = s.replaceAll("(?i)(?:SN|序列号|设备标识|条码号?)\\s*[:：]\\s*", "");
        s = s.replaceAll("^[;；,，\\s]+", "");
        s = s.replaceAll("[;；,，\\s]+$", "");
        s = s.replaceAll("[;；,，]\\s*[;；,，]+", "；");
        r.extra = s.trim();
    }

    /**
     * 标签上通常只印一组 SSID 和密码，5G 靠 -5G 后缀推导。
     * 这里把缺失的一半补齐，让界面上两个框都有内容。
     */
    private static void deriveBands(Result r) {
        // 双频合一的设备只有一个名字，硬推 -5G 会造出一个不存在的网络
        if (r.ssid5g.isEmpty() && !r.ssid24g.isEmpty()
                && r.hasSeparate5g && !looks5g(r.ssid24g)) {
            r.ssid5g = r.ssid24g + "-5G";
        }
        if (r.ssid24g.isEmpty() && !r.ssid5g.isEmpty()) {
            String base = strip5g(r.ssid5g);
            if (!base.equals(r.ssid5g)) {
                r.ssid24g = base;
            }
        }
        // 两个频段密码一致是常态，缺一个就补上
        if (r.pwd5g.isEmpty() && !r.pwd24g.isEmpty()) {
            r.pwd5g = r.pwd24g;
        } else if (r.pwd24g.isEmpty() && !r.pwd5g.isEmpty()) {
            r.pwd24g = r.pwd5g;
        }
    }

    private static boolean looks5g(String ssid) {
        return ssid.toLowerCase().endsWith("5g") || ssid.toLowerCase().endsWith("5ghz");
    }

    /** 去掉结尾的 -5G / _5G / 5G / 5GHz，得到 2.4G 的名字。 */
    public static String strip5g(String ssid) {
        String s = ssid.trim();
        String r = s.replaceAll("(?i)[\\-_ ]?5ghz$", "");
        r = r.replaceAll("(?i)[\\-_ ]?5g$", "");
        return r.isEmpty() ? s : r;
    }

    private static String clean(String s) {
        if (s == null) {
            return "";
        }
        String v = s.trim();
        if (v.equalsIgnoreCase("null") || v.equals("无") || v.equals("空")) {
            return "";
        }
        // 去掉整体包裹的引号
        if (v.length() >= 2) {
            char a = v.charAt(0);
            char b = v.charAt(v.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) {
                v = v.substring(1, v.length() - 1).trim();
            }
        }
        return v;
    }

    private static String normalizeUrl(String v) {
        String u = v.trim();
        if (u.isEmpty()) {
            return "192.168.1.1";
        }
        u = u.replaceAll("\\s+", "");
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "http://" + u;
        }
        // 去掉尾部斜杠，保留路径
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    private static String endpoint(String base) {
        String u = base == null ? "" : base.trim();
        if (u.isEmpty()) {
            u = "https://ark.cn-beijing.volces.com/api/v3";
        }
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        if (!u.endsWith("/chat/completions")) {
            u = u + "/chat/completions";
        }
        return u;
    }

    private static String readAll(InputStream is) {
        if (is == null) {
            return "";
        }
        try (InputStream in = is) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toString("UTF-8");
        } catch (IOException e) {
            return "";
        }
    }

    private static String brief(String err) {
        if (err == null) {
            return "";
        }
        String e = err.replaceAll("\\s+", " ").trim();
        return e.length() > 220 ? e.substring(0, 220) + "…" : e;
    }

    /** 读图 + 压缩到指定长边，控制上传体积。 */
    public static byte[] loadAndCompress(Context ctx, Uri uri, int maxSide) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
            if (is == null) {
                throw new IOException("无法打开照片");
            }
            BitmapFactory.decodeStream(is, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("照片读取失败，请重拍");
        }

        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        int sample = 1;
        while (longest / (sample * 2) >= maxSide) {
            sample *= 2;
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bmp;
        try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
            if (is == null) {
                throw new IOException("无法打开照片");
            }
            bmp = BitmapFactory.decodeStream(is, null, opts);
        }
        if (bmp == null) {
            throw new IOException("照片解码失败，请重拍");
        }

        // 相机常把方向写在 EXIF 里而不是真的旋转像素。
        // 不矫正的话，模型看到的是躺倒的标签，既慢又容易认错。
        bmp = applyExifOrientation(ctx, uri, bmp);

        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int cur = Math.max(w, h);
        if (cur > maxSide) {
            float f = maxSide / (float) cur;
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(w * f)),
                    Math.max(1, Math.round(h * f)), true);
            if (scaled != bmp) {
                bmp.recycle();
            }
            bmp = scaled;
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 88, bos);
        bmp.recycle();
        return bos.toByteArray();
    }

    private VisionClient() {
    }

    /** 按 EXIF 里的方向标记把照片摆正。相机竖拍时像素常常是横的。 */
    private static Bitmap applyExifOrientation(Context ctx, Uri uri, Bitmap src) {
        int orientation;
        try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
            if (is == null) {
                return src;
            }
            ExifInterface exif = new ExifInterface(is);
            orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception e) {
            return src;
        }
        if (orientation == ExifInterface.ORIENTATION_NORMAL
                || orientation == ExifInterface.ORIENTATION_UNDEFINED) {
            return src;
        }

        Matrix m = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                m.postRotate(90);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                m.postRotate(180);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                m.postRotate(270);
                break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                m.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                m.postScale(1, -1);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                m.postRotate(90);
                m.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                m.postRotate(270);
                m.postScale(-1, 1);
                break;
            default:
                return src;
        }
        try {
            Bitmap rotated = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
            if (rotated != src) {
                src.recycle();
            }
            return rotated;
        } catch (Exception e) {
            return src;
        }
    }
}
