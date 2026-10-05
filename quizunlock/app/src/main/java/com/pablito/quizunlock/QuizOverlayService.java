package com.pablito.quizunlock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Random;

public class QuizOverlayService extends Service {
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "quiz_unlock";

    private WindowManager windowManager;
    private View overlay;
    private BroadcastReceiver screenReceiver;
    private Question currentQuestion;
    private boolean registered = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startAsForeground();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        registerScreenReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean enabled = getSharedPreferences("settings", MODE_PRIVATE)
                .getBoolean("enabled", false);
        if (enabled && Settings.canDrawOverlays(this)) {
            showQuestion();
        }
        return START_STICKY;
    }

    private void startAsForeground() {
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Quiz Unlock está activo")
                .setContentText("El teléfono pide una pregunta al encenderse la pantalla")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Quiz Unlock", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Servicio que mantiene activo el quiz de desbloqueo");
        nm.createNotificationChannel(channel);
    }

    private void registerScreenReceiver() {
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    hideQuestion();
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    showQuestion();
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, filter);
        }
        registered = true;
    }

    private void showQuestion() {
        if (!getSharedPreferences("settings", MODE_PRIVATE).getBoolean("enabled", false)) return;
        if (!Settings.canDrawOverlays(this)) return;
        if (overlay != null) return;

        List<Question> questions = QuestionBank.load(this);
        if (questions.isEmpty()) {
            Toast.makeText(this, "No hay preguntas válidas", Toast.LENGTH_LONG).show();
            return;
        }

        currentQuestion = questions.get(new Random().nextInt(questions.size()));

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(35, 50, 35, 50);
        root.setBackgroundColor(Color.rgb(245, 248, 252));
        root.setFocusableInTouchMode(true);
        root.setOnKeyListener((v, keyCode, event) -> keyCode == KeyEvent.KEYCODE_BACK);

        TextView title = new TextView(this);
        title.setText("🔐  RESPONDE PARA USAR EL TELÉFONO");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, weightParams());

        TextView question = new TextView(this);
        question.setText(currentQuestion.text);
        question.setTextSize(22);
        question.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        question.setGravity(Gravity.CENTER);
        question.setPadding(10, 30, 10, 30);
        root.addView(question, weightParams());

        for (int i = 0; i < 4; i++) {
            final int index = i;
            Button b = new Button(this);
            b.setText((char) ('A' + i) + ")  " + currentQuestion.options[i]);
            b.setTextSize(18);
            b.setAllCaps(false);
            b.setOnClickListener(v -> answer(index));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, 0, 1f);
            p.setMargins(0, 8, 0, 8);
            root.addView(b, p);
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_SECURE,
                android.graphics.PixelFormat.OPAQUE);
        params.gravity = Gravity.TOP | Gravity.START;
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;

        overlay = root;
        try {
            windowManager.addView(overlay, params);
            overlay.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            overlay.requestFocus();
        } catch (Exception e) {
            overlay = null;
        }
    }

    private LinearLayout.LayoutParams weightParams() {
        return new LinearLayout.LayoutParams(-1, 0, 1f);
    }

    private void answer(int selectedIndex) {
        if (currentQuestion == null) return;

        if (selectedIndex == currentQuestion.correctIndex) {
            Toast.makeText(this, "✅ Correcta", Toast.LENGTH_SHORT).show();
            hideQuestion();
        } else {
            Toast.makeText(this, "❌ Incorrecta. Inténtalo otra vez.", Toast.LENGTH_SHORT).show();
        }
    }

    private void hideQuestion() {
        if (overlay != null) {
            try {
                windowManager.removeViewImmediate(overlay);
            } catch (Exception ignored) {
            }
            overlay = null;
        }
        currentQuestion = null;
    }

    @Override
    public void onDestroy() {
        hideQuestion();
        if (registered && screenReceiver != null) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (Exception ignored) {
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
