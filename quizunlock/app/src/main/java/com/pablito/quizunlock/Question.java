package com.pablito.quizunlock;

public class Question {
    public final String text;
    public final String[] options;
    public final int correctIndex;

    public Question(String text, String[] options, int correctIndex) {
        this.text = text;
        this.options = options;
        this.correctIndex = correctIndex;
    }
}
