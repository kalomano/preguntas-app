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
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.app.KeyguardManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.ScaleAnimation;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public class QuizOverlayService extends Service {
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "quiz_unlock";
    private static final long SIX_MINUTES_MS = 6L * 60L * 1000L;

    private WindowManager windowManager;
    private View overlay;
    private BroadcastReceiver screenReceiver;
    private Question currentQuestion;
    private boolean registered = false;
    private final Handler unlockHandler = new Handler(Looper.getMainLooper());
    private Runnable unlockCheck;

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
        maybeShowQuestion();
        return START_STICKY;
    }

    private void startAsForeground() {
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Quiz Unlock activo")
                .setContentText("Te pregunta al desbloquear después de 6 minutos")
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
        channel.setDescription("Mantiene activo el quiz de estudio");
        nm.createNotificationChannel(channel);
    }

    private void registerScreenReceiver() {
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();

                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    unlockHandler.removeCallbacksAndMessages(null);
                    hideQuestion();
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    scheduleUnlockCheck();
                } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                    maybeShowQuestion();
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);\n        filter.addAction(Intent.ACTION_SCREEN_ON);\n        filter.addAction(Intent.ACTION_USER_PRESENT);\n
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, filter);
        }
        registered = true;
    }

    private void scheduleUnlockCheck() {
        unlockHandler.removeCallbacksAndMessages(null);
        unlockCheck = new Runnable() {
            int attempts = 0;
            @Override public void run() {
                attempts++;
                KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
                boolean locked = km != null && km.isKeyguardLocked();
                if (!locked) {
                    maybeShowQuestion();
                    return;
                }
                if (attempts < 20) unlockHandler.postDelayed(this, 500L);
            }
        };
        unlockHandler.postDelayed(unlockCheck, 700L);
    }

    private void maybeShowQuestion() {
        if (!getSharedPreferences("settings", MODE_PRIVATE)
                .getBoolean("enabled", false)) return;
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) return;
        KeyguardManager keyguardManager = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (keyguardManager != null && keyguardManager.isKeyguardLocked()) return;
        if (overlay != null) return;

        List<Question> questions = QuestionBank.loadAll(this);
        if (questions.isEmpty()) return;

        Question pending = getPendingQuestion(questions);
        long lastQuestionAt = getSharedPreferences("settings", MODE_PRIVATE)
                .getLong("lastQuestionAt",
                        getSharedPreferences("settings", MODE_PRIVATE)
                                .getLong("lastAnsweredAt", 0L));

        if (lastQuestionAt != 0L
                && System.currentTimeMillis() - lastQuestionAt < SIX_MINUTES_MS) {
            return;
        }

        if (pending != null) {
            showQuiz(pending, isFailedYesterday(pending));
            return;
        }

        Question selected = chooseQuestion(questions);
        if (selected == null) return;

        getSharedPreferences("settings", MODE_PRIVATE)
                .edit()
                .putString("pendingQuestionId", selected.id)
                .putLong("lastQuestionAt", System.currentTimeMillis())
                .apply();

        showQuiz(selected, isFailedYesterday(selected));
    }

    private Question getPendingQuestion(List<Question> questions) {
        String id = getSharedPreferences("settings", MODE_PRIVATE)
                .getString("pendingQuestionId", "");
        if (id == null || id.isEmpty()) return null;

        for (Question q : questions) {
            if (id.equals(q.id)) return q;
        }

        getSharedPreferences("settings", MODE_PRIVATE)
                .edit()
                .remove("pendingQuestionId")
                .apply();
        return null;
    }

    private Question chooseQuestion(List<Question> questions) {
        List<Question> priority = new ArrayList<>();
        for (Question q : questions) {
            if (isFailedYesterday(q)) priority.add(q);
        }

        if (!priority.isEmpty()) {
            return priority.get(new Random().nextInt(priority.size()));
        }

        return questions.get(new Random().nextInt(questions.size()));
    }

    private boolean isFailedYesterday(Question question) {
        String failedDate = getSharedPreferences("settings", MODE_PRIVATE)
                .getString(failedKey(question.id), "");
        return yesterdayKey().equals(failedDate);
    }

    private String failedKey(String questionId) {
        return "failed_" + questionId;
    }

    private String todayKey() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                .format(new Date());
    }

    private String yesterdayKey() {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_YEAR, -1);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                .format(calendar.getTime());
    }

    private void showQuiz(Question question, boolean failedYesterday) {
        if (overlay != null) return;
        currentQuestion = question;

        FrameLayout root = createRoot();
        overlay = root;

        showQuizContent(root, question, failedYesterday);

        try {
            windowManager.addView(overlay, buildWindowParams());
            enterAnimation(root);
            root.requestFocus();
        } catch (Exception e) {
            overlay = null;
            currentQuestion = null;
        }
    }

    private void showQuizContent(FrameLayout root, Question question, boolean failedYesterday) {
        root.removeAllViews();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(22), dp(34), dp(22), dp(28));

        TextView eyebrow = makeText(
                failedYesterday ? "⚠️ RECUPERACIÓN DE AYER" : "QUIZ UNLOCK",
                13,
                failedYesterday ? Color.rgb(255, 198, 92) : Color.rgb(129, 190, 255),
                Typeface.BOLD);
        eyebrow.setGravity(Gravity.CENTER);
        content.addView(eyebrow, wrap());

        TextView title = makeText("Responde para continuar", 27, Color.WHITE, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        content.addView(title, marginTop(6));

        if (failedYesterday) {
            TextView reminder = makeText(
                    "La fallaste ayer. Hoy vuelve a salir entre las primeras.",
                    15, Color.rgb(255, 220, 160), Typeface.NORMAL);
            reminder.setGravity(Gravity.CENTER);
            content.addView(reminder, marginTop(7));
        }

        LinearLayout questionCard = makeCard(Color.rgb(248, 250, 253));
        TextView questionText = makeText(question.text, 24,
                Color.rgb(17, 28, 44), Typeface.BOLD);
        questionText.setGravity(Gravity.CENTER);
        questionText.setLineSpacing(0f, 1.12f);
        questionText.setPadding(dp(10), dp(4), dp(10), dp(6));
        questionCard.addView(questionText, wrap());
        content.addView(questionCard, marginTop(20));

        for (int i = 0; i < 4; i++) {
            Button option = makeOptionButton(i, question.options[i]);
            final int selected = i;
            option.setOnClickListener(v -> answer(selected));
            content.addView(option, marginTop(10));
        }

        TextView bank = makeText(
                "Conjunto: " + question.bankName,
                12, Color.rgb(142, 160, 184), Typeface.NORMAL);
        bank.setGravity(Gravity.CENTER);
        content.addView(bank, marginTop(18));

        scroll.addView(content);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
    }

    private void answer(int selectedIndex) {
        if (currentQuestion == null) return;

        if (selectedIndex == currentQuestion.correctIndex) {
            handleCorrect(selectedIndex);
        } else {
            handleWrong(selectedIndex);
        }
    }

    private void handleWrong(int selectedIndex) {
        String now = todayKey();
        getSharedPreferences("settings", MODE_PRIVATE)
                .edit()
                .putString(failedKey(currentQuestion.id), now)
                .apply();

        FrameLayout root = (FrameLayout) overlay;
        showFailureFeedback(root, selectedIndex);
    }

    private void showFailureFeedback(FrameLayout root, int selectedIndex) {
        root.removeAllViews();

        LinearLayout content = feedbackContainer();

        TextView icon = makeText("✕", 72, Color.rgb(255, 115, 115), Typeface.BOLD);
        icon.setGravity(Gravity.CENTER);
        content.addView(icon, wrap());

        TextView title = makeText("Has fallado", 32, Color.WHITE, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        content.addView(title, marginTop(8));

        TextView yours = makeText(
                "Tu respuesta:\n" + currentQuestion.options[selectedIndex],
                18, Color.rgb(229, 235, 243), Typeface.NORMAL);
        yours.setGravity(Gravity.CENTER);
        content.addView(yours, marginTop(18));

        TextView correct = makeText(
                "Respuesta correcta:\n" + currentQuestion.options[currentQuestion.correctIndex],
                20, Color.rgb(139, 255, 174), Typeface.BOLD);
        correct.setGravity(Gravity.CENTER);
        content.addView(correct, marginTop(16));

        TextView tomorrow = makeText(
                "⚠️ Quedará priorizada para mañana.",
                15, Color.rgb(255, 210, 125), Typeface.NORMAL);
        tomorrow.setGravity(Gravity.CENTER);
        content.addView(tomorrow, marginTop(18));

        Button retry = makeFeedbackButton("INTENTAR DE NUEVO");
        retry.setOnClickListener(v ->
                showQuizContent(root, currentQuestion, false));
        content.addView(retry, marginTop(28));

        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        feedbackAnimation(content);
    }

    private void handleCorrect(int selectedIndex) {
        boolean recovered = isFailedYesterday(currentQuestion);

        getSharedPreferences("settings", MODE_PRIVATE)
                .edit()
                .putLong("lastQuestionAt", System.currentTimeMillis())
                .remove("pendingQuestionId")
                .remove(failedKey(currentQuestion.id))
                .apply();

        FrameLayout root = (FrameLayout) overlay;
        showSuccessFeedback(root, selectedIndex, recovered);
    }

    private void showSuccessFeedback(FrameLayout root, int selectedIndex, boolean recovered) {
        root.removeAllViews();

        LinearLayout content = feedbackContainer();

        TextView icon = makeText("✓", 78, Color.rgb(117, 255, 160), Typeface.BOLD);
        icon.setGravity(Gravity.CENTER);
        content.addView(icon, wrap());

        TextView title = makeText("¡Correcta!", 34, Color.WHITE, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        content.addView(title, marginTop(8));

        TextView response = makeText(
                "Tu respuesta:\n" + currentQuestion.options[selectedIndex],
                19, Color.rgb(224, 234, 243), Typeface.NORMAL);
        response.setGravity(Gravity.CENTER);
        content.addView(response, marginTop(18));

        TextView note = makeText(
                recovered
                        ? "🔥 Pregunta recuperada. Ya no queda pendiente."
                        : "Bien. La próxima será cuando hayan pasado 6 minutos.",
                16, Color.rgb(144, 255, 178), Typeface.NORMAL);
        note.setGravity(Gravity.CENTER);
        content.addView(note, marginTop(18));

        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        feedbackAnimation(content);

        root.postDelayed(this::hideQuestion, 1500L);
    }

    private LinearLayout feedbackContainer() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(dp(28), dp(30), dp(28), dp(30));
        return content;
    }

    private void feedbackAnimation(View view) {
        AnimationSet set = new AnimationSet(true);
        set.setInterpolator(new AccelerateDecelerateInterpolator());

        AlphaAnimation alpha = new AlphaAnimation(0f, 1f);
        alpha.setDuration(260);

        ScaleAnimation scale = new ScaleAnimation(
                0.72f, 1f, 0.72f, 1f,
                Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 0.5f);
        scale.setDuration(320);

        set.addAnimation(alpha);
        set.addAnimation(scale);
        view.startAnimation(set);
    }

    private FrameLayout createRoot() {
        FrameLayout root = new FrameLayout(this);
        root.setFocusableInTouchMode(true);
        root.setOnKeyListener((v, keyCode, event) ->
                keyCode == KeyEvent.KEYCODE_BACK);

        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[] {
                        Color.rgb(7, 15, 28),
                        Color.rgb(17, 40, 62),
                        Color.rgb(8, 18, 34)
                });
        root.setBackground(bg);
        return root;
    }

    private WindowManager.LayoutParams buildWindowParams() {
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
        params.x = 0;
        params.y = 0;
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;

        if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }

        return params;
    }

    private void enterAnimation(View view) {
        view.setAlpha(0f);
        view.animate()
                .alpha(1f)
                .setDuration(220L)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();

        view.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private Button makeOptionButton(int index, String text) {
        Button button = makeFeedbackButton((char) ('A' + index) + "   " + text);
        button.setTextSize(18);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setPadding(dp(18), dp(15), dp(18), dp(15));
        button.setMinHeight(dp(64));
        return button;
    }

    private Button makeFeedbackButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setTextColor(Color.WHITE);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(56));
        button.setPadding(dp(18), dp(10), dp(18), dp(10));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(38, 87, 125));
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), Color.rgb(78, 126, 161));
        button.setBackground(bg);
        return button;
    }

    private LinearLayout makeCard(int color) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(22), dp(22), dp(22));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(26));
        card.setBackground(bg);
        return card;
    }

    private TextView makeText(String value, float size, int color, int style) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT, style);
        return t;
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
