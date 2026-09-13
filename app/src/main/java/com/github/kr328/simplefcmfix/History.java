package com.github.kr328.simplefcmfix;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public class History {
    private static final int MAX_RECORDS = 50;
    private final LinkedList<HistoryRecord> records = new LinkedList<>();

    public void addRecord(final HistoryRecord record) {
        records.add(record);

        if (records.size() > MAX_RECORDS) {
            records.removeFirst();
        }
    }

    public List<HistoryRecord> getRecords() {
        return Collections.unmodifiableList(records);
    }

}
