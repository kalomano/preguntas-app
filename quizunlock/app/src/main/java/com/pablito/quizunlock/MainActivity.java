package com.pablito.quizunlock;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_FILE = 42;
    private static final long SIX_MINUTES_MS = 6L * 60L * 1000L;
    private SharedPreferences prefs;
    private LinearLayout banksContainer;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        buildUi();

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 43);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (banksContainer != null) refreshBanks();
        if (statusText != null) refreshStatus();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(9, 17, 30));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(28));

        TextView eyebrow = text("QUIZ UNLOCK", 13, Color.rgb(112, 174, 255), Typeface.BOLD);
        root.addView(eyebrow, wrap());

        TextView title = text("Tu móvil te hace estudiar.", 30, Color.WHITE, Typeface.BOLD);
        title.setPadding(0, dp(5), 0, dp(4));
        root.addView(title, wrap());

        TextView subtitle = text(
                "Pregunta al desbloquear cuando llevas 6 minutos sin contestar.\n"
                        + "Las que falles vuelven a tener prioridad al día siguiente.",
                16, Color.rgb(196, 208, 224), Typeface.NORMAL);
        root.addView(subtitle, wrap());

        LinearLayout statusCard = card();
        TextView statusTitle = text("ESTADO", 12, Color.rgb(112, 174, 255), Typeface.BOLD);
        statusCard.addView(statusTitle, wrap());
        statusText = text("", 16, Color.WHITE, Typeface.BOLD);
        statusText.setPadding(0, dp(8), 0, 0);
        statusCard.addView(statusText, wrap());
        root.addView(statusCard, marginTop(20));

        Button permission = primaryButton("Permitir mostrar sobre otras apps");
        permission.setOnClickListener(v -> requestOverlayPermission());
        root.addView(permission, marginTop(14));

        Button importBtn = primaryButton("＋  Importar conjunto de preguntas");
        importBtn.setOnClickListener(v -> openCsvPicker());
        root.addView(importBtn, marginTop(10));

        TextView section = text("CONJUNTOS IMPORTADOS", 12,
                Color.rgb(142, 157, 176), Typeface.BOLD);
        section.setPadding(0, dp(28), 0, dp(10));
        root.addView(section, wrap());

        banksContainer = new LinearLayout(this);
        banksContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(banksContainer, wrap());

        Button enable = primaryButton("ACTIVAR QUIZ");
        enable.setOnClickListener(v -> enableLock());
        root.addView(enable, marginTop(18));

        Button disable = secondaryButton("Desactivar quiz");
        disable.setOnClickListener(v -> disableLock());
        root.addView(disable, marginTop(10));

        TextView format = text(
                "Formato: Pregunta;A;B;C;D;Correcta.\n"
                        + "Correcta = 1–4 o A–D. También admite CSV con comas y campos entre comillas.",
                13, Color.rgb(148, 161, 179), Typeface.NORMAL);
        format.setPadding(0, dp(24), 0, 0);
        root.addView(format, wrap());

        scroll.addView(root);
        setContentView(scroll);
        refreshBanks();
        refreshStatus();
    }

    private void refreshBanks() {
        if (banksContainer == null) return;
        banksContainer.removeAllViews();

        List<QuestionBank.BankInfo> banks = QuestionBank.listBanks(this);
        if (banks.isEmpty()) {
            TextView empty = text("No hay conjuntos importados todavía.", 15,
                    Color.rgb(170, 181, 196), Typeface.NORMAL);
            empty.setPadding(dp(4), dp(10), dp(4), dp(10));
            banksContainer.addView(empty, wrap());
            return;
        }

        for (QuestionBank.BankInfo bank : banks) {
            LinearLayout row = card();
            row.setPadding(dp(16), dp(14), dp(10), dp(14));

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);

            TextView name = text(bank.name, 17, Color.WHITE, Typeface.BOLD);
            TextView count = text(
                    bank.count + (bank.count == 1 ? " pregunta" : " preguntas"),
                    14, Color.rgb(165, 178, 196), Typeface.NORMAL);
            info.addView(name, wrap());
            info.addView(count, marginTop(4));

            row.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));

            Button delete = new Button(this);
            delete.setText("Borrar");
            delete.setTextSize(13);
            delete.setAllCaps(false);
            delete.setTextColor(Color.rgb(255, 160, 160));
            delete.setOnClickListener(v -> confirmDelete(bank));
            row.addView(delete, new LinearLayout.LayoutParams(dp(82), dp(48)));

            banksContainer.addView(row, marginTop(8));
        }
    }

    private void refreshStatus() {
        if (statusText == null) return;

        long last = prefs.getLong("lastAnsweredAt", 0L);
        boolean enabled = prefs.getBoolean("enabled", false);

        if (!enabled) {
            statusText.setText("Desactivado");
            return;
        }

        if (last == 0L) {
            statusText.setText("Activo · primera pregunta al próximo desbloqueo");
            return;
        }

        long remaining = Math.max(0L, SIX_MINUTES_MS - (System.currentTimeMillis() - last));
        if (remaining == 0L) {
            statusText.setText("Activo · ya toca pregunta al próximo desbloqueo");
        } else {
            long min = remaining / 60_000L;
            long sec = (remaining % 60_000L) / 1000L;
            statusText.setText(String.format(Locale.getDefault(),
                    "Activo · próxima elegible en ~%02d:%02d", min, sec));
        }
    }

    private void confirmDelete(QuestionBank.BankInfo bank) {
        new AlertDialog.Builder(this)
                .setTitle("Borrar conjunto")
                .setMessage("Se eliminará \\"" + bank.name + "\\" y sus preguntas.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Borrar", (dialog, which) -> {
                    QuestionBank.deleteBank(this, bank.id);
                    Toast.makeText(this, "Conjunto eliminado", Toast.LENGTH_SHORT).show();
                    refreshBanks();
                    refreshStatus();
                })
                .show();
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
        intent.setType("*/*");
        startActivityForResult(intent, REQ_FILE);
    }

    private void showImportNameDialog(Uri uri) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(getFileName(uri));
        input.setSelectAllOnFocus(true);
        input.setHint("Nombre del conjunto");

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Nombre del conjunto")
                .setMessage("Ese nombre aparecerá en la aplicación al gestionar tus bancos.")
                .setView(input)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Importar", null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String name = input.getText().toString().trim();
                    try {
                        int count = QuestionBank.importBank(this, uri, name);
                        Toast.makeText(this,
                                "Importadas " + count + " preguntas",
                                Toast.LENGTH_LONG).show();
                        dialog.dismiss();
                        refreshBanks();
                    } catch (Exception e) {
                        Toast.makeText(this,
                                e.getMessage() == null
                                        ? "No se pudo importar el CSV."
                                        : e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                }));

        dialog.show();
    }

    private String getFileName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME},
                null,
                null,
                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.trim().isEmpty()) {
                    return stripExtension(name.trim());
                }
            }
        } catch (Exception ignored) {
        }
        return "Nuevo conjunto";
    }

    private String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private void enableLock() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this,
                    "Primero concede el permiso de superposición.",
                    Toast.LENGTH_LONG).show();
            requestOverlayPermission();
            return;
        }

        if (QuestionBank.loadAll(this).isEmpty()) {
            Toast.makeText(this,
                    "Importa al menos un conjunto con preguntas válidas.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        prefs.edit().putBoolean("enabled", true).apply();

        Intent service = new Intent(this, QuizOverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(service);
        } else {
            startService(service);
        }

        Toast.makeText(this,
                "Quiz activado · pregunta al desbloquear tras 6 minutos",
                Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    private void disableLock() {
        prefs.edit().putBoolean("enabled", false).apply();
        stopService(new Intent(this, QuizOverlayService.class));
        Toast.makeText(this, "Quiz desactivado", Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != REQ_FILE
                || resultCode != RESULT_OK
                || data == null
                || data.getData() == null) {
            return;
        }

        showImportNameDialog(data.getData());
    }

    private TextView text(String value, float size, int color, int style) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT, style);
        return t;
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(18), dp(18), dp(18));

        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.rgb(17, 29, 47));
        bg.setCornerRadius(dp(22));
        bg.setStroke(dp(1), Color.rgb(41, 58, 80));
        box.setBackground(bg);

        return box;
    }

    private Button primaryButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(16);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setMinHeight(dp(54));
        b.setPadding(dp(14), dp(8), dp(14), dp(8));

        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.rgb(43, 111, 215));
        bg.setCornerRadius(dp(18));
        b.setBackground(bg);

        return b;
    }

    private Button secondaryButton(String label) {
        Button b = primaryButton(label);
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.rgb(26, 40, 61));
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), Color.rgb(57, 76, 101));
        b.setBackground(bg);
        return b;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private LinearLayout.LayoutParams marginTop(int top) {
        LinearLayout.LayoutParams p = wrap();
        p.topMargin = dp(top);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
