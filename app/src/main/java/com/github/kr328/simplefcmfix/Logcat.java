package com.github.kr328.simplefcmfix;

import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class Logcat implements AutoCloseable {
    private final Process process;
    private final BufferedReader reader;

    private boolean closed;

    public Logcat(@Nullable final Level level, @Nullable final String filter) throws IOException {
        final List<String> command = new ArrayList<>();
        command.add("logcat");
        command.add("-T");
        command.add("0");
        command.add("-s");

        if (filter != null) {
            command.add(level == null ? filter : filter + ":" + level.priority);
        } else if (level != null) {
            command.add("*:" + level.priority);
        }

        process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8));
    }

    @Nullable
    public String readLine() throws IOException {
        return reader.readLine();
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;

        process.destroy();
        if (process.isAlive()) {
            process.destroyForcibly();
        }

        reader.close();
    }

    public enum Level {
        VERBOSE('V'),
        DEBUG('D'),
        INFO('I'),
        WARN('W'),
        ERROR('E'),
        FATAL('F');

        private final char priority;

        Level(final char priority) {
            this.priority = priority;
        }
    }
}
