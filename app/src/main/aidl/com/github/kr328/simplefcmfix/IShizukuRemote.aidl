package com.github.kr328.simplefcmfix;

interface IShizukuRemote {
    void destroy() = 16777114;

    boolean isRunning() = 1;
    void start() = 2;
    void stop() = 3;
    String[] getNoRestrictApps() = 4;
}
