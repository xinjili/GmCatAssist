package com.gmcat.assist;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_SHOOT = 101;
    private static final int REQ_ADD_WIFI = 102;
    private static final int REQ_PERM = 103;

    private static final int KIND_OK = 0;
    private static final int KIND_WARN = 1;
    private static final int KIND_ERROR = 2;
    private static final int KIND_INFO = 3;

    private Button btnShoot;
    private Button btnConnect5g;
    private Button btnConnect24g;
    private Button btnAdmin;
    private Button btnAdminTop;
    private TextView btnSettings;
    private TextView tvSubtitle;
    private TextView tvStatus;
    private TextView tvLoading;
    private TextView tvNote;
    private TextView btnCopyAll;
    private TextView tvAdminUrlHint;
    private TextView tvSn;
    private TextView btnCopySn;

    private LinearLayout boxLoading;
    private LinearLayout boxAdminTop;
    private LinearLayout card5g;
    private LinearLayout card24g;
    private LinearLayout cardDevice;
    private LinearLayout deviceRows;
    private LinearLayout snBox;

    private TextView dot5g;
    private TextView dot24g;
    private TextView hint5g;
    private TextView hint24g;

    private EditText etSsid5g;
    private EditText etPwd5g;
    private EditText etSsid24g;
    private EditText etPwd24g;
    private EditText etAdminUrl;
    private EditText etAdminUser;
    private EditText etAdminPwd;

    private Uri pendingPhotoUri;
    private VisionClient.Result lastResult;
    private String targetSsid = "";
    private boolean afterPermissionAction = false;
    private boolean pendingWant5g = true;

    /** 当前推荐的频段：默认 5G。 */
    private boolean use5g = true;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        bindClicks();
        applySelection();

        if (!Prefs.ready(this)) {
            showStatus(KIND_WARN, "识别接口还没配置。点右上角「设置」填入 API Key 和模型名，先配一次就好。");
        }
    }

    private void bindViews() {
        btnShoot = findViewById(R.id.btnShoot);
        btnConnect5g = findViewById(R.id.btnConnect5g);
        btnConnect24g = findViewById(R.id.btnConnect24g);
        btnAdmin = findViewById(R.id.btnAdmin);
        btnAdminTop = findViewById(R.id.btnAdminTop);
        btnSettings = findViewById(R.id.btnSettings);
        tvSubtitle = findViewById(R.id.tvSubtitle);
        tvStatus = findViewById(R.id.tvStatus);
        tvLoading = findViewById(R.id.tvLoading);
        tvNote = findViewById(R.id.tvNote);
        btnCopyAll = findViewById(R.id.btnCopyAll);
        tvAdminUrlHint = findViewById(R.id.tvAdminUrlHint);
        tvSn = findViewById(R.id.tvSn);
        btnCopySn = findViewById(R.id.btnCopySn);

        boxLoading = findViewById(R.id.boxLoading);
        boxAdminTop = findViewById(R.id.boxAdminTop);
        card5g = findViewById(R.id.card5g);
        card24g = findViewById(R.id.card24g);
        cardDevice = findViewById(R.id.cardDevice);
        deviceRows = findViewById(R.id.deviceRows);
        snBox = findViewById(R.id.snBox);

        dot5g = findViewById(R.id.dot5g);
        dot24g = findViewById(R.id.dot24g);
        hint5g = findViewById(R.id.hint5g);
        hint24g = findViewById(R.id.hint24g);

        etSsid5g = findViewById(R.id.etSsid5g);
        etPwd5g = findViewById(R.id.etPwd5g);
        etSsid24g = findViewById(R.id.etSsid24g);
        etPwd24g = findViewById(R.id.etPwd24g);
        etAdminUrl = findViewById(R.id.etAdminUrl);
        etAdminUser = findViewById(R.id.etAdminUser);
        etAdminPwd = findViewById(R.id.etAdminPwd);
    }

    private void bindClicks() {
        btnShoot.setOnClickListener(v -> shoot());
        btnAdmin.setOnClickListener(v -> openAdmin());
        btnAdminTop.setOnClickListener(v -> openAdmin());
        btnSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        // 点卡片只是切换推荐项，连接由卡片内的按钮触发
        card5g.setOnClickListener(v -> {
            use5g = true;
            applySelection();
        });
        card24g.setOnClickListener(v -> {
            use5g = false;
            applySelection();
        });

        btnConnect5g.setOnClickListener(v -> startConnect(true));
        btnConnect24g.setOnClickListener(v -> startConnect(false));

        // 名字对不上时，直接从附近列表里挑
        hint5g.setOnClickListener(v -> showScanListDialog(true));
        hint24g.setOnClickListener(v -> showScanListDialog(false));

        btnCopyAll.setOnClickListener(v -> {
            copy(buildAllText());
            toast("全部信息已复制");
        });

        btnCopySn.setOnClickListener(v -> {
            String sn = tvSn.getText().toString().trim();
            if (!sn.isEmpty()) {
                copy(sn);
                toast("SN 已复制");
            }
        });
    }

    // ---------------- 频段选择 ----------------

    private void applySelection() {
        card5g.setBackgroundResource(use5g
                ? R.drawable.bg_card_selected : R.drawable.bg_card);
        card24g.setBackgroundResource(use5g
                ? R.drawable.bg_card : R.drawable.bg_card_selected);

        dot5g.setText(use5g ? "●" : "○");
        dot24g.setText(use5g ? "○" : "●");
        dot5g.setTextColor(getColor(use5g ? R.color.brand : R.color.text_hint));
        dot24g.setTextColor(getColor(use5g ? R.color.text_hint : R.color.brand));

        // 推荐的那个用实心按钮，另一个用描边，一眼能分出主次
        btnConnect5g.setBackgroundResource(use5g
                ? R.drawable.bg_btn_primary : R.drawable.bg_btn_ghost);
        btnConnect5g.setTextColor(getColor(use5g ? R.color.white : R.color.brand));
        btnConnect24g.setBackgroundResource(use5g
                ? R.drawable.bg_btn_ghost : R.drawable.bg_btn_primary);
        btnConnect24g.setTextColor(getColor(use5g ? R.color.brand : R.color.white));
    }

    // ---------------- 拍照与识别 ----------------

    private void shoot() {
        if (!Prefs.ready(this)) {
            showStatus(KIND_WARN, "先点右上角「设置」，填入 API Key 和模型名。");
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME,
                    "gmcat_" + System.currentTimeMillis() + ".jpg");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            pendingPhotoUri = getContentResolver()
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (pendingPhotoUri == null) {
                showStatus(KIND_ERROR, "创建照片文件失败，换个相机或检查存储空间。");
                return;
            }
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, pendingPhotoUri);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intent, REQ_SHOOT);
        } catch (ActivityNotFoundException e) {
            showStatus(KIND_ERROR, "没找到可用的相机应用。");
        } catch (Exception e) {
            showStatus(KIND_ERROR, "打开相机失败：" + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_SHOOT) {
            if (resultCode == RESULT_OK && pendingPhotoUri != null) {
                recognize(pendingPhotoUri);
            } else if (pendingPhotoUri != null) {
                deleteQuietly(pendingPhotoUri);
                pendingPhotoUri = null;
            }
            return;
        }

        if (requestCode == REQ_ADD_WIFI) {
            onAddNetworkReturned(resultCode);
        }
    }

    private void recognize(Uri uri) {
        setLoading(true, "正在识别标签…");
        tvStatus.setVisibility(View.GONE);
        final Uri photo = uri;
        io.execute(() -> {
            try {
                VisionClient.Result r = VisionClient.recognize(MainActivity.this, photo);
                ui.post(() -> {
                    setLoading(false);
                    onRecognized(r);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    setLoading(false);
                    showStatus(KIND_ERROR, friendly(e));
                });
            } finally {
                ui.postDelayed(() -> deleteQuietly(photo), 1200);
            }
        });
    }

    private void onRecognized(VisionClient.Result r) {
        if (r == null || !r.usable()) {
            lastResult = null;
            card5g.setVisibility(View.GONE);
            card24g.setVisibility(View.GONE);
            cardDevice.setVisibility(View.GONE);
            boxAdminTop.setVisibility(View.GONE);
            btnAdmin.setVisibility(View.GONE);
            String extra = (r != null && !r.note.isEmpty()) ? "（" + r.note + "）" : "";
            showStatus(KIND_ERROR,
                    "没能从标签上认出可用的无线信息" + extra + "。换个角度、避开反光再拍一张。");
            return;
        }

        lastResult = r;
        etSsid5g.setText(r.ssid5g);
        etPwd5g.setText(r.pwd5g);
        etSsid24g.setText(r.ssid24g);
        etPwd24g.setText(r.pwd24g);
        etAdminUrl.setText(r.adminUrl);
        etAdminUser.setText(r.adminUser);
        etAdminPwd.setText(r.adminPassword);

        showDeviceInfo(r);

        card5g.setVisibility(View.VISIBLE);
        card24g.setVisibility(View.VISIBLE);
        btnAdmin.setVisibility(View.GONE);
        btnShoot.setText(R.string.reshoot);
        tvSubtitle.setText("点卡片切换频段，字段都能直接改");

        // 顶部进后台：识别出来就能点，旁边带上地址
        boxAdminTop.setVisibility(View.VISIBLE);
        String hint = r.adminUrl.replaceFirst("(?i)^https?://", "");
        tvAdminUrlHint.setText("（" + hint + "）");

        // 默认 5G，等扫描结果出来再按实际覆盖
        use5g = true;
        applySelection();

        if (!r.note.isEmpty() || "low".equals(r.confidence) || "medium".equals(r.confidence)) {
            tvNote.setVisibility(View.VISIBLE);
            String why = r.note.isEmpty() ? "识别把握不大" : r.note;
            tvNote.setText("提示：" + why + "，建议核对标签原件");
        } else {
            tvNote.setVisibility(View.GONE);
        }

        checkPassword(r);
        scanBands();
    }

    /** 密码不合规时提前拦下，免得白连一次。 */
    private void checkPassword(VisionClient.Result r) {
        String pwd5 = r.pwd5g;
        String pwd24 = r.pwd24g;
        String bad = null;

        if (!pwd5.isEmpty() && !WifiHelper.passphraseValid(pwd5)) {
            bad = "5G 密码只有 " + pwd5.length() + " 位";
        } else if (!pwd5.isEmpty() && !cleanPassword(pwd5)) {
            bad = "5G 密码里混进了空格或全角字符";
        } else if (!pwd24.isEmpty() && !WifiHelper.passphraseValid(pwd24)) {
            bad = "2.4G 密码只有 " + pwd24.length() + " 位";
        } else if (!pwd24.isEmpty() && !cleanPassword(pwd24)) {
            bad = "2.4G 密码里混进了空格或全角字符";
        }

        if (bad != null) {
            showStatus(KIND_WARN, bad + "。正常 WPA 密码是 8 位以上纯英文数字，"
                    + "多半是 0/O、1/l 这类字符认错了，核对一下再连。");
        } else {
            tvStatus.setVisibility(View.GONE);
        }
    }

    private void showDeviceInfo(VisionClient.Result r) {
        // SN 是后台认证的关键项，单独放大显示在最上面
        if (r.sn.isEmpty()) {
            snBox.setVisibility(View.GONE);
        } else {
            tvSn.setText(r.sn);
            snBox.setVisibility(View.VISIBLE);
        }

        deviceRows.removeAllViews();
        int count = 0;
        count += addDeviceRow("MAC", r.mac);
        count += addDeviceRow("型号", r.model);
        count += addDeviceRow("其他", r.extra);

        // 这张卡里还有后台登录字段，所以始终显示
        cardDevice.setVisibility(View.VISIBLE);
        if (count == 0) {
            deviceRows.setVisibility(View.GONE);
        } else {
            deviceRows.setVisibility(View.VISIBLE);
        }
    }

    /** 生成一行「标签 值 复制」，返回 1 表示加了一行。 */
    private int addDeviceRow(String label, String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));

        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextSize(12);
        tvLabel.setTextColor(getColor(R.color.text_secondary));
        tvLabel.setWidth(dp(62));
        row.addView(tvLabel);

        TextView tvValue = new TextView(this);
        tvValue.setText(value);
        tvValue.setTextSize(13);
        tvValue.setTextColor(getColor(R.color.text_primary));
        tvValue.setTypeface(Typeface.MONOSPACE);
        tvValue.setTextIsSelectable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvValue.setLayoutParams(lp);
        row.addView(tvValue);

        TextView tvCopy = new TextView(this);
        tvCopy.setText("复制");
        tvCopy.setTextSize(12);
        tvCopy.setTextColor(getColor(R.color.brand));
        tvCopy.setPadding(dp(12), dp(6), dp(2), dp(6));
        tvCopy.setOnClickListener(v -> {
            copy(value);
            toast("已复制 " + label);
        });
        row.addView(tvCopy);

        deviceRows.addView(row);
        return 1;
    }

    /**
     * 扫一下周边，给两个频段标上「已扫到 / 未扫到」。
     * 5G 不在而 2.4G 在时，自动把推荐项切到 2.4G。
     */
    private void scanBands() {
        WifiManager wm = WifiHelper.wifi(this);
        if (wm == null || !wm.isWifiEnabled()) {
            setHint(hint5g, "WiFi 未开", R.color.text_hint);
            setHint(hint24g, "", R.color.text_hint);
            return;
        }
        if (checkSelfPermission(WifiHelper.permission()) != PackageManager.PERMISSION_GRANTED) {
            setHint(hint5g, "待授权", R.color.text_hint);
            setHint(hint24g, "", R.color.text_hint);
            return;
        }
        // Android 12 及以下扫 WiFi 依赖定位权限，定位开关关着的话结果恒为空
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                && !WifiHelper.locationEnabled(this)) {
            setHint(hint5g, "需开定位", R.color.warn);
            setHint(hint24g, "", R.color.text_hint);
            return;
        }

        final String s5 = etSsid5g.getText().toString().trim();
        final String s24 = etSsid24g.getText().toString().trim();

        setHint(hint5g, "扫描中", R.color.text_secondary);
        setHint(hint24g, "扫描中", R.color.text_secondary);

        io.execute(() -> {
            List<ScanResult> results = WifiHelper.scanNow(MainActivity.this);
            boolean scanOk = results != null && !results.isEmpty();
            int total = results == null ? 0 : results.size();
            boolean has5 = WifiHelper.has(results, s5);
            boolean has24 = WifiHelper.has(results, s24);
            // 名字差一两个字符的情况很常见（识别偏差 / 光猫命名和标签不符），找出来提示用户
            String near5 = has5 ? null : WifiHelper.nearest(results, s5, 4);

            ui.post(() -> {
                if (!scanOk) {
                    // 没拿到列表 ≠ 没有这个网络，此时不能替用户改频段
                    setHint(hint5g, "未检测", R.color.text_hint);
                    setHint(hint24g, "", R.color.text_hint);
                    return;
                }
                // 只在扫到时报喜，扫不到用中性措辞：扫描结果不可靠，不能断言网络不存在
                setHint(hint5g, has5 ? "已扫到" : "未检测到",
                        has5 ? R.color.ok : R.color.text_hint);
                setHint(hint24g, has24 ? "已扫到" : "未检测到",
                        has24 ? R.color.ok : R.color.text_hint);

                // 不再根据扫描结果自动改推荐频段。部分机型扫描列表不完整，
                // 会自动把用户从 5G 挪到 2.4G，反而添乱。推荐项交给用户自己点。

                // 只有发现「名字非常接近但不是同一个」时才提示，这通常意味着识别出了偏差
                if (!has5 && near5 != null) {
                    showStatus(KIND_INFO, "5G 没检测到「" + s5 + "」，但附近有「" + near5
                            + "」。如果这是同一台光猫，点 5G 卡片的无线名称改成它。"
                            + "扫描结果仅供参考，不影响连接。");
                } else if (!has5 && !has24 && total > 0) {
                    showStatus(KIND_INFO, "扫描只拿到 " + total + " 个网络，都不匹配。"
                            + "部分机型扫描受限属正常现象，仍可正常连接，不影响使用。");
                }
            });
        });
    }

    private void setHint(TextView tv, String text, int colorRes) {
        tv.setText(text);
        tv.setTextColor(getColor(colorRes));
    }

    /**
     * 列出附近扫到的 WiFi，点一项就填进对应的频段。
     * 用来解决「标签上推出来的名字和光猫实际名字不一致」的情况。
     */
    private void showScanListDialog(boolean for5g) {
        if (checkSelfPermission(WifiHelper.permission()) != PackageManager.PERMISSION_GRANTED) {
            toast("没有「附近的设备」权限，读不到周边列表");
            return;
        }
        WifiManager wm = WifiHelper.wifi(this);
        if (wm == null || !wm.isWifiEnabled()) {
            toast("WiFi 没打开");
            return;
        }

        setLoading(true, "正在扫描附近网络…");
        io.execute(() -> {
            List<ScanResult> results = WifiHelper.scanNow(this);
            ui.post(() -> {
                setLoading(false);
                if (results == null || results.isEmpty()) {
                    toast("没拿到周边 WiFi 列表，可能是权限或系统限制");
                    return;
                }
                List<ScanResult> list = new ArrayList<>();
                for (ScanResult r : results) {
                    if (r.SSID != null && !r.SSID.isEmpty()) {
                        list.add(r);
                    }
                }
                list.sort((a, b) -> Integer.compare(b.level, a.level));
                if (list.size() > 40) {
                    list = new ArrayList<>(list.subList(0, 40));
                }
                final List<ScanResult> items = list;

                String[] labels = new String[items.size()];
                for (int i = 0; i < items.size(); i++) {
                    ScanResult r = items.get(i);
                    labels[i] = r.SSID + "   (" + r.level + "dBm)";
                }

                new AlertDialog.Builder(this)
                        .setTitle(for5g ? "选一个作为 5G 名称" : "选一个作为 2.4G 名称")
                        .setItems(labels, (d, which) -> {
                            String picked = items.get(which).SSID;
                            if (for5g) {
                                etSsid5g.setText(picked);
                            } else {
                                etSsid24g.setText(picked);
                            }
                            scanBands();
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
        });
    }

    // ---------------- 连接 ----------------

    private void startConnect(boolean want5g) {
        if (lastResult == null) {
            return;
        }
        use5g = want5g;
        applySelection();

        final String ssid = (want5g ? etSsid5g : etSsid24g).getText().toString().trim();
        final String pwd = (want5g ? etPwd5g : etPwd24g).getText().toString().trim();

        if (ssid.isEmpty()) {
            showStatus(KIND_ERROR, "无线名称是空的，填一个再连。");
            return;
        }
        if (!WifiHelper.passphraseValid(pwd)) {
            showStatus(KIND_WARN, "密码是 " + pwd.length()
                    + " 位，WPA/WPA2 要求 8–63 位。核对一下标签，可能是 0/O、1/l 这类字符认错了。");
            return;
        }
        if (!cleanPassword(pwd)) {
            showStatus(KIND_WARN, "密码里有空格、中文或全角字符，先改正再连。");
            return;
        }

        if (checkSelfPermission(WifiHelper.permission()) != PackageManager.PERMISSION_GRANTED) {
            afterPermissionAction = true;
            pendingWant5g = want5g;
            requestPermissions(new String[]{WifiHelper.permission()}, REQ_PERM);
            return;
        }

        // 仅 Android 12 及以下需要：定位权限以外，系统定位开关也必须打开
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                && !WifiHelper.locationEnabled(this)) {
            showStatus(KIND_WARN, "Android 12 及以下扫周边 WiFi 必须打开系统「位置信息」开关"
                    + "（设置 → 位置信息），否则判断不了 5G 在不在。连接仍会继续尝试。");
        }

        WifiManager wm = WifiHelper.wifi(this);
        if (wm == null) {
            showStatus(KIND_ERROR, "这台设备没有 WiFi 模块。");
            return;
        }
        if (!wm.isWifiEnabled()) {
            showStatus(KIND_WARN, "手机 WiFi 是关着的，先打开再点连接。");
            try {
                startActivity(new Intent(Settings.Panel.ACTION_WIFI));
            } catch (Exception ignore) {
                try {
                    startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
                } catch (Exception ignore2) {
                    // 忽略
                }
            }
            return;
        }

        setLoading(true, "正在扫周边，确认信号…");
        io.execute(() -> {
            List<ScanResult> results = WifiHelper.scanNow(this);
            boolean scanOk = results != null && !results.isEmpty();
            boolean nearMine = WifiHelper.has(results, ssid);
            ui.post(() -> {
                setLoading(false);
                resolveAndConnect(ssid, pwd, nearMine, scanOk);
            });
        });
    }

    /**
     * 连接决策。注意：扫描结果只用来提示，绝不据此改变用户选择的频段。
     * 部分机型 getScanResults() 返回的列表不完整（SSID 脱敏或受节流影响），
     * 据此自动切换会把本来能连上的网络换掉。
     */
    private void resolveAndConnect(String ssid, String pwd, boolean nearMine, boolean scanOk) {
        String target = ssid;
        String targetPwd = pwd;

        if (!scanOk) {
            showStatus(KIND_INFO, "没拿到周边 WiFi 列表（部分机型限制），"
                    + "直接按你的选择连接「" + ssid + "」。");
        } else if (!nearMine) {
            showStatus(KIND_INFO, "扫描列表里没看到「" + ssid
                    + "」，可能只是扫描受限，仍按你的选择尝试连接。");
        } else {
            showStatus(KIND_OK, "扫到「" + target + "」，正在调起系统连接。");
        }

        targetSsid = target;

        try {
            startActivityForResult(WifiHelper.addNetworkIntent(target, targetPwd), REQ_ADD_WIFI);
        } catch (ActivityNotFoundException e) {
            fallbackManual(target, targetPwd);
        } catch (IllegalArgumentException e) {
            showStatus(KIND_ERROR, "密码被系统判定为不合法（" + e.getMessage()
                    + "）。核对一下标签上是不是有容易混淆的字符。");
        } catch (Exception e) {
            fallbackManual(target, targetPwd);
        }
    }

    /** 系统不支持预填弹窗时的兜底：复制密码 + 打开 WiFi 设置。 */
    private void fallbackManual(String target, String pwd) {
        copy(pwd);
        showStatus(KIND_WARN,
                "这台手机没提供系统预填弹窗。密码已复制到剪贴板，"
                        + "在 WiFi 列表里选「" + target + "」粘贴密码即可。");
        try {
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
        } catch (Exception ignore) {
            // 忽略
        }
    }

    private void onAddNetworkReturned(int resultCode) {
        if (resultCode == RESULT_OK) {
            showStatus(KIND_OK, "已提交给系统。连上后如果没外网是正常的——光猫还没开通，进后台走内网。");
            btnAdmin.setVisibility(View.VISIBLE);
            ui.postDelayed(() -> checkConnected(3), 2500);
        } else {
            // 部分机型从系统页面返回时不带结果，不能直接当失败，按实际连接状态判断
            showStatus(KIND_INFO, "正在确认连接状态…");
            ui.postDelayed(() -> checkConnected(3), 1500);
        }
    }

    /** 轮询确认是否真的连到了目标 SSID。attempts 是剩余重试次数。 */
    private void checkConnected(int attempts) {
        String cur = WifiHelper.currentSsid(this);
        if (!cur.isEmpty() && WifiHelper.norm(cur).equals(WifiHelper.norm(targetSsid))) {
            showStatus(KIND_OK, "已经连上「" + cur + "」。可以直接进后台。");
            btnAdmin.setVisibility(View.VISIBLE);
            return;
        }
        if (attempts > 0) {
            ui.postDelayed(() -> checkConnected(attempts - 1), 4000);
            return;
        }
        showStatus(KIND_WARN, "还没连上。可以再点一次连接，或在系统 WiFi 列表里手动选「"
                + targetSsid + "」。");
    }

    // ---------------- 进后台 ----------------

    private void openAdmin() {
        String url = etAdminUrl.getText().toString().trim();
        if (url.isEmpty()) {
            url = "192.168.1.1";
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }

        // 没连光猫就进后台是打不开的，先提醒一句，别让人对着浏览器报错页发愣
        String cur = WifiHelper.currentSsid(this);
        String s5 = etSsid5g.getText().toString().trim();
        String s24 = etSsid24g.getText().toString().trim();
        boolean onCat = !cur.isEmpty()
                && (WifiHelper.norm(cur).equals(WifiHelper.norm(s5))
                || WifiHelper.norm(cur).equals(WifiHelper.norm(s24)));
        if (!onCat) {
            toast("还没连光猫 WiFi，后台可能打不开");
        }

        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            copy(url);
            showStatus(KIND_ERROR, "没找到可用的浏览器。地址已复制，手动打开即可。");
        } catch (Exception e) {
            showStatus(KIND_ERROR, "打开浏览器失败：" + e.getMessage());
        }
    }

    // ---------------- 复制 ----------------

    /** 拼一份能直接发给工单或同事的纯文本。 */
    private String buildAllText() {
        StringBuilder sb = new StringBuilder();

        if (lastResult != null && !lastResult.sn.isEmpty()) {
            sb.append("SN: ").append(lastResult.sn).append('\n');
        }
        if (lastResult != null && !lastResult.mac.isEmpty()) {
            sb.append("MAC: ").append(lastResult.mac).append('\n');
        }
        if (lastResult != null && !lastResult.model.isEmpty()) {
            sb.append("型号: ").append(lastResult.model).append('\n');
        }
        if (lastResult != null && !lastResult.extra.isEmpty()) {
            sb.append("其他: ").append(lastResult.extra).append('\n');
        }

        String wifi5 = etSsid5g.getText().toString().trim();
        if (!wifi5.isEmpty()) {
            sb.append("WiFi 5G: ").append(wifi5)
                    .append(" / ").append(etPwd5g.getText().toString().trim()).append('\n');
        }
        String wifi24 = etSsid24g.getText().toString().trim();
        if (!wifi24.isEmpty()) {
            sb.append("WiFi 2.4G: ").append(wifi24)
                    .append(" / ").append(etPwd24g.getText().toString().trim()).append('\n');
        }

        String adminUrl = etAdminUrl.getText().toString().trim();
        if (!adminUrl.isEmpty()) {
            sb.append("后台: ").append(adminUrl).append('\n');
            sb.append("后台账号: ").append(etAdminUser.getText().toString().trim()).append('\n');
            sb.append("后台密码: ").append(etAdminPwd.getText().toString().trim());
        }
        return sb.toString().trim();
    }

    // ---------------- 权限 ----------------

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERM) {
            return;
        }
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            scanBands();
            if (afterPermissionAction) {
                afterPermissionAction = false;
                startConnect(pendingWant5g);
            }
        } else {
            afterPermissionAction = false;
            showStatus(KIND_WARN,
                    "不给权限就扫不到周边 WiFi，也就判断不了 5G 在不在。"
                            + "可以照样连，只是没有「已扫到」提示。");
        }
    }

    // ---------------- UI 辅助 ----------------

    private void setLoading(boolean loading) {
        setLoading(loading, "");
    }

    private void setLoading(boolean loading, String message) {
        boxLoading.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (message != null && !message.isEmpty()) {
            tvLoading.setText(message);
        }
        btnShoot.setEnabled(!loading);
        btnConnect5g.setEnabled(!loading);
        btnConnect24g.setEnabled(!loading);
    }

    private void showStatus(int kind, String message) {
        int bg;
        int fg;
        if (kind == KIND_OK) {
            bg = R.color.ok_bg;
            fg = R.color.ok;
        } else if (kind == KIND_WARN) {
            bg = R.color.warn_bg;
            fg = R.color.warn;
        } else if (kind == KIND_INFO) {
            bg = R.color.brand_light;
            fg = R.color.brand;
        } else {
            bg = R.color.danger_bg;
            fg = R.color.danger;
        }
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(10));
        shape.setColor(getColor(bg));
        shape.setStroke(dp(1), blend(getColor(bg)));
        tvStatus.setBackground(shape);
        tvStatus.setTextColor(getColor(fg));
        tvStatus.setText(message);
        tvStatus.setVisibility(View.VISIBLE);
    }

    /** 让边框比底色略深一点，视觉上更清楚。 */
    private int blend(int color) {
        int r = (int) (android.graphics.Color.red(color) * 0.82);
        int g = (int) (android.graphics.Color.green(color) * 0.82);
        int b = (int) (android.graphics.Color.blue(color) * 0.82);
        return android.graphics.Color.rgb(r, g, b);
    }

    private String friendly(Exception e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        String low = msg.toLowerCase();
        if (low.contains("timeout") || msg.contains("超时")) {
            return "识别超时。现场信号差的话，走到有信号的地方再试一次。";
        }
        if (low.contains("unable to resolve host") || low.contains("unknownhost")
                || low.contains("failed to connect")) {
            return "连不上识别服务器。检查一下手机有没有流量。";
        }
        if (msg.contains("401") || low.contains("unauthorized") || low.contains("invalid api")) {
            return "API Key 不对或已失效，去设置里检查一下。";
        }
        if (msg.contains("404")) {
            return "接口地址或模型名不对（404）。检查 Base URL 和模型名。";
        }
        if (msg.contains("429")) {
            return "调用太频繁或额度用完了（429）。";
        }
        if (msg.contains("识别接口返回")) {
            return msg;
        }
        return msg.isEmpty() ? "识别失败，重拍一张试试。" : msg;
    }

    private void copy(String text) {
        if (text == null) {
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("gmcat", text));
        }
    }

    /** 光猫 WiFi 密码基本都是 ASCII 可见字符且不含空格，混进别的就是识别出错。 */
    private boolean cleanPassword(String p) {
        if (p.isEmpty()) {
            return false;
        }
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    private void deleteQuietly(Uri uri) {
        try {
            getContentResolver().delete(uri, null, null);
        } catch (Exception ignore) {
            // 删不掉就算了
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        String cur = WifiHelper.currentSsid(this);
        if (!cur.isEmpty() && !targetSsid.isEmpty()
                && WifiHelper.norm(cur).equals(WifiHelper.norm(targetSsid))) {
            btnAdmin.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }
}
