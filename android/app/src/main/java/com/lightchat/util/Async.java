package com.lightchat.util;

import android.app.Activity;

public final class Async {
    private Async() {}

    public interface Worker<T> {
        T run() throws Exception;
    }

    public interface UI<T> {
        void on(T result, Exception error);
    }

    public static <T> void exec(Activity activity, Worker<T> w, UI<T> ui) {
        new Thread(new Runnable() {
            @Override public void run() {
                final Result<T> r = new Result<T>();
                try {
                    r.value = w.run();
                } catch (Exception e) {
                    r.error = e;
                }
                activity.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        ui.on(r.value, r.error);
                    }
                });
            }
        }).start();
    }

    private static final class Result<T> {
        T value;
        Exception error;
    }
}