package com.pablito.quizunlock;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class MainActivity extends Activity {
    private static final int REQ_FILE = 42;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        buildUi();

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 43);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 40, 40, 40);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("QUIZ UNLOCK");
        title.setTextSize(30);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView info = new TextView(this);
        info.setText("Cada vez que la pantalla se encienda aparecerá una pregunta aleatoria.\n\nPrimero prueba todo con el PIN del sistema todavía activado.");
        info.setTextSize(17);
        info.setPadding(0, 25, 0, 25);
        root.addView(info, new LinearLayout.LayoutParams(-1, -2));

        Button permission = new Button(this);
        permission.setText("1. Dar permiso para mostrar sobre otras apps");
        permission.setOnClickListener(v -> requestOverlayPermission());
        root.addView(permission, buttonParams());

        Button importBtn = new Button(this);
        importBtn.setText("2. Importar banco de preguntas CSV");
        importBtn.setOnClickListener(v -> openCsvPicker());
        root.addView(importBtn, buttonParams());

        Button enable = new Button(this);
        enable.setText("3. ACTIVAR bloqueo");
        enable.setOnClickListener(v -> enableLock());
        root.addView(enable, buttonParams());

        Button disable = new Button(this);
        disable.setText("DESACTIVAR bloqueo");
        disable.setOnClickListener(v -> disableLock());
        root.addView(disable, buttonParams());

        TextView format = new TextView(this);
        format.setText("Formato CSV: Pregunta;Respuesta A;Respuesta B;Respuesta C;Respuesta D;Correcta\n\nCorrecta = 1, 2, 3 o 4. Puedes tener cientos de líneas.");
        format.setTextSize(15);
        format.setPadding(0, 25, 0, 0);
        root.addView(format, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 12, 0, 0);
        return p;
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } else {
            Toast.makeText(this, "Permiso ya concedido", Toast.LENGTH_SHORT).show();
        }
    }

    private void openCsvPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/*");
        startActivityForResult(intent, REQ_FILE);
    }

    private void enableLock() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Primero concede el permiso de superposición", Toast.LENGTH_LONG).show();
            requestOverlayPermission();
            return;
        }

        if (QuestionBank.load(this).isEmpty()) {
            Toast.makeText(this, "No hay preguntas válidas", Toast.LENGTH_LONG).show();
            return;
        }

        prefs.edit().putBoolean("enabled", true).apply();
        Intent service = new Intent(this, QuizOverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(service);
        } else {
            startService(service);
        }
        Toast.makeText(this, "Bloqueo activado", Toast.LENGTH_SHORT).show();
    }

    private void disableLock() {
        prefs.edit().putBoolean("enabled", false).apply();
        stopService(new Intent(this, QuizOverlayService.class));
        Toast.makeText(this, "Bloqueo desactivado", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        Uri uri = data.getData();
        try {
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "questions.csv"))) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            }
            int count = QuestionBank.load(this).size();
            Toast.makeText(this, "Importadas " + count + " preguntas", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo importar el CSV", Toast.LENGTH_LONG).show();
        }
    }
}
