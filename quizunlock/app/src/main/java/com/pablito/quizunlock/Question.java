package com.pablito.quizunlock;

public class Question {
    public final String id;
    public final String bankId;
    public final String bankName;
    public final String text;
    public final String[] options;
    public final int correctIndex;

    public Question(String id, String bankId, String bankName,
                    String text, String[] options, int correctIndex) {
        this.id = id;
        this.bankId = bankId;
        this.bankName = bankName;
        this.text = text;
        this.options = options;
        this.correctIndex = correctIndex;
    }
}
