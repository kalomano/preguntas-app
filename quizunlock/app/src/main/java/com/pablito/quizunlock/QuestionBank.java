package com.pablito.quizunlock;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class QuestionBank {
    private static final String PREFS = "settings";
    private static final String BANK_PREFIX = "bank_";
    private static final String BANK_SUFFIX = ".csv";
    private static final String LEGACY_FILE_NAME = "questions.csv";

    public static final class BankInfo {
        public final String id;
        public final String name;
        public final int count;

        public BankInfo(String id, String name, int count) {
            this.id = id;
            this.name = name;
            this.count = count;
        }
    }

    private QuestionBank() {}

    public static List<BankInfo> listBanks(Context context) {
        migrateLegacyIfNeeded(context);
        List<BankInfo> result = new ArrayList<>();
        File dir = context.getFilesDir();
        File[] files = dir.listFiles((d, name) ->
                name.startsWith(BANK_PREFIX) && name.endsWith(BANK_SUFFIX));
        if (files == null) return result;

        Arrays.sort(files, Comparator.comparing(File::getName));
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        for (File file : files) {
            String id = file.getName().substring(BANK_PREFIX.length(),
                    file.getName().length() - BANK_SUFFIX.length());
            String name = prefs.getString("bank_" + id + "_name", "Conjunto " + id);
            int count = parseFile(file, id, name).size();
            result.add(new BankInfo(id, name, count));
        }
        return result;
    }

    public static int importBank(Context context, Uri uri, String displayName) throws Exception {
        String cleanName = sanitizeName(displayName);
        String id = Long.toString(System.currentTimeMillis());
        File destination = new File(context.getFilesDir(), BANK_PREFIX + id + BANK_SUFFIX);

        try (InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(destination)) {
            if (in == null) throw new IllegalStateException("No se pudo abrir el archivo");
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }

        int count = parseFile(destination, id, cleanName).size();
        if (count == 0) {
            //noinspection ResultOfMethodCallIgnored
            destination.delete();
            throw new IllegalArgumentException("El CSV no contiene preguntas válidas.");
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("bank_" + id + "_name", cleanName)
                .apply();

        return count;
    }

    public static boolean deleteBank(Context context, String bankId) {
        File file = new File(context.getFilesDir(), BANK_PREFIX + bankId + BANK_SUFFIX);
        boolean deleted = !file.exists() || file.delete();

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove("bank_" + bankId + "_name")
                .apply();

        return deleted;
    }

    public static List<Question> load(Context context) {
        return loadAll(context);
    }

    public static List<Question> loadAll(Context context) {
        migrateLegacyIfNeeded(context);
        List<Question> result = new ArrayList<>();

        File[] files = context.getFilesDir().listFiles((d, name) ->
                name.startsWith(BANK_PREFIX) && name.endsWith(BANK_SUFFIX));

        if (files == null) return result;
        Arrays.sort(files, Comparator.comparing(File::getName));

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        for (File file : files) {
            String id = file.getName().substring(BANK_PREFIX.length(),
                    file.getName().length() - BANK_SUFFIX.length());
            String name = prefs.getString("bank_" + id + "_name", "Conjunto " + id);
            result.addAll(parseFile(file, id, name));
        }
        return result;
    }

    public static Question findById(Context context, String questionId) {
        if (questionId == null || questionId.isEmpty()) return null;
        for (Question q : loadAll(context)) {
            if (questionId.equals(q.id)) return q;
        }
        return null;
    }

    private static void migrateLegacyIfNeeded(Context context) {
        File legacy = new File(context.getFilesDir(), LEGACY_FILE_NAME);
        if (!legacy.exists()) return;

        File[] banks = context.getFilesDir().listFiles((d, name) ->
                name.startsWith(BANK_PREFIX) && name.endsWith(BANK_SUFFIX));
        if (banks != null && banks.length > 0) {
            //noinspection ResultOfMethodCallIgnored
            legacy.delete();
            return;
        }

        String id = "legacy_" + System.currentTimeMillis();
        File destination = new File(context.getFilesDir(), BANK_PREFIX + id + BANK_SUFFIX);
        if (copyFile(legacy, destination)) {
            int count = parseFile(destination, id, "Banco anterior").size();
            if (count > 0) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putString("bank_" + id + "_name", "Banco anterior")
                        .apply();
            } else {
                //noinspection ResultOfMethodCallIgnored
                destination.delete();
            }
        }
        //noinspection ResultOfMethodCallIgnored
        legacy.delete();
    }

    private static boolean copyFile(File source, File destination) {
        try (InputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static List<Question> parseFile(File file, String bankId, String bankName) {
        List<Question> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {

            String line;
            char delimiter = ';';
            boolean delimiterDetected = false;

            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                if (line.length() > 0 && line.charAt(0) == '﻿') {
                    line = line.substring(1);
                }

                if (!delimiterDetected) {
                    delimiter = detectDelimiter(line);
                    delimiterDetected = true;
                }

                List<String> fields = parseDelimitedLine(line, delimiter);
                if (fields.size() == 1 && delimiter == ';') {
                    fields = parseDelimitedLine(line, ',');
                }

                if (fields.size() != 6) continue;

                String first = fields.get(0).trim();
                String last = fields.get(5).trim();

                if (first.equalsIgnoreCase("Pregunta")
                        || first.equalsIgnoreCase("Question")) {
                    continue;
                }

                int correct = parseCorrect(last);
                if (correct < 0 || correct > 3) continue;

                String questionText = fields.get(0).trim();
                String[] options = new String[] {
                        fields.get(1).trim(),
                        fields.get(2).trim(),
                        fields.get(3).trim(),
                        fields.get(4).trim()
                };

                if (questionText.isEmpty()
                        || options[0].isEmpty()
                        || options[1].isEmpty()
                        || options[2].isEmpty()
                        || options[3].isEmpty()) {
                    continue;
                }

                String id = makeId(bankId, questionText, options);
                result.add(new Question(id, bankId, bankName, questionText, options, correct));
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private static int parseCorrect(String value) {
        try {
            return Integer.parseInt(value) - 1;
        } catch (NumberFormatException ignored) {
        }

        if ("A".equalsIgnoreCase(value)) return 0;
        if ("B".equalsIgnoreCase(value)) return 1;
        if ("C".equalsIgnoreCase(value)) return 2;
        if ("D".equalsIgnoreCase(value)) return 3;
        return -1;
    }

    private static char detectDelimiter(String line) {
        return line.indexOf(';') >= 0 ? ';' : ',';
    }

    private static List<String> parseDelimitedLine(String line, char delimiter) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == delimiter && !quoted) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }

        fields.add(current.toString());
        return fields;
    }

    private static String sanitizeName(String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) clean = "Nuevo conjunto";
        clean = clean.replaceAll("[\\/:*?\"<>|]", "_");
        if (clean.length() > 60) clean = clean.substring(0, 60);
        return clean;
    }

    private static String makeId(String bankId, String question, String[] options) {
        StringBuilder source = new StringBuilder(bankId).append("|").append(question);
        for (String option : options) {
            source.append("|").append(option);
        }

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(source.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(source.toString().hashCode());
        }
    }
}
