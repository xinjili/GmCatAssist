package com.gmcat.assist;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

public class SettingsActivity extends Activity {

    private EditText etBaseUrl;
    private EditText etApiKey;
    private EditText etModel;
    private EditText etSpeedUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        etBaseUrl = findViewById(R.id.etBaseUrl);
        etApiKey = findViewById(R.id.etApiKey);
        etModel = findViewById(R.id.etModel);
        etSpeedUrl = findViewById(R.id.etSpeedUrl);
        Button btnSave = findViewById(R.id.btnSave);
        Button btnBack = findViewById(R.id.btnBack);

        etBaseUrl.setText(Prefs.baseUrl(this));
        etApiKey.setText(Prefs.apiKey(this));
        etModel.setText(Prefs.model(this));
        etSpeedUrl.setText(Prefs.speedUrl(this));

        // 方便核对 Key 有没有填错
        etApiKey.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        etApiKey.setSelection(etApiKey.getText().length());

        btnSave.setOnClickListener(v -> {
            String base = etBaseUrl.getText().toString().trim();
            String key = etApiKey.getText().toString().trim();
            String model = etModel.getText().toString().trim();
            String speed = etSpeedUrl.getText().toString().trim();

            if (base.isEmpty()) {
                base = "https://ark.cn-beijing.volces.com/api/v3";
            }
            if (!base.startsWith("http")) {
                Toast.makeText(this, "Base URL 要以 http:// 或 https:// 开头",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            if (key.isEmpty() || model.isEmpty()) {
                Toast.makeText(this, "Key 和模型名都要填", Toast.LENGTH_SHORT).show();
                return;
            }
            if (speed.isEmpty()) {
                speed = "https://www.speedtest.cn/";
            }
            if (!speed.startsWith("http")) {
                speed = "http://" + speed;
            }

            Prefs.save(this, base, key, model, speed);
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
            finish();
        });

        btnBack.setOnClickListener(v -> finish());
    }
}
