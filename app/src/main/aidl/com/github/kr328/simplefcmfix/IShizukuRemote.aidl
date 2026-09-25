package com.github.kr328.simplefcmfix;

import com.github.kr328.simplefcmfix.HistoryRecord;
import com.github.kr328.simplefcmfix.AppStatus;

interface IShizukuRemote {
    void destroy() = 16777114;

    boolean isRunning() = 1;
    void start() = 2;
    void stop() = 3;
    String[] getNoRestrictApps() = 4;
    HistoryRecord[] getHistory() = 5;
    AppStatus[] listApps() = 6;
}
