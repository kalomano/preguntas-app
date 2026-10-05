package com.pablito.quizunlock;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class QuestionBank {
    private static final String FILE_NAME = "questions.csv";

    private QuestionBank() {}

    public static List<Question> load(Context context) {
        List<Question> result = new ArrayList<>();
        try {
            File local = new File(context.getFilesDir(), FILE_NAME);
            InputStream input;
            if (local.exists()) {
                input = new FileInputStream(local);
            } else {
                input = context.getAssets().open(FILE_NAME);
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty() || line.startsWith("Pregunta;")) continue;
                    String[] p = line.split(";", -1);
                    if (p.length != 6) continue;
                    int correct;
                    try {
                        correct = Integer.parseInt(p[5].trim()) - 1;
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    if (correct < 0 || correct > 3) continue;
                    String[] options = new String[]{p[1], p[2], p[3], p[4]};
                    result.add(new Question(p[0], options, correct));
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }
}
