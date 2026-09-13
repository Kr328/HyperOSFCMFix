package android.os;

interface IPowerManager {
    void wakeUp(long time, int reason, in String details, in String opPackageName);
}
